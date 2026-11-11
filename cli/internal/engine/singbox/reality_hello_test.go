//go:build with_utls

package singbox

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"io"
	"net"
	"net/http"
	"strconv"
	"testing"
	"time"

	sbtls "github.com/sagernet/sing-box/common/tls"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/net/proxy"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

const (
	groupX25519         = 0x001d
	groupX25519MLKEM768 = 0x11ec
	extSupportedGroups  = 0x000a
	extKeyShare         = 0x0033
)

// TestRealityHelloOpensOnCurrentXray reads the Reality hello the front sends,
// off the wire, for every fingerprint a link may name, and holds it to what
// Xray servers check before they answer:
//   - an X25519MLKEM768 key share ahead of any X25519 one (XTLS/REALITY
//     8cdf7bf, Xray-core >= v26.9.8; without it the hello is handed to the
//     camouflage target);
//   - a sealed session id that opens with the server's key and announces a
//     version >= 26.3.27 (the minClientVer Xray-core v26.7.x applies when the
//     server sets none).
//
// Needs no server and no network: the "server" is a listener that keeps the
// first record it is sent.
func TestRealityHelloOpensOnCurrentXray(t *testing.T) {
	serverKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pub := base64.RawURLEncoding.EncodeToString(serverKey.PublicKey().Bytes())
	for _, fp := range []string{"chrome", "", "firefox", "safari", "ios", "edge", "android", "360", "qq", "random", "randomized"} {
		name := fp
		if name == "" {
			name = "unnamed"
		}
		t.Run(name, func(t *testing.T) {
			hello := captureRealityHello(t, fp, pub, "0123abcd")
			groups, shares := helloGroupsAndShares(t, hello)
			if !contains(groups, groupX25519MLKEM768) {
				t.Errorf("supported_groups %x has no X25519MLKEM768", groups)
			}
			order := shareGroups(shares)
			hybrid, classic := indexOf(order, groupX25519MLKEM768), indexOf(order, groupX25519)
			if hybrid < 0 || (classic >= 0 && classic < hybrid) {
				t.Fatalf("key shares %x: Xray >= v26.9.8 needs an X25519MLKEM768 share ahead of X25519", order)
			}
			plain := openSessionID(t, hello, serverKey, shares)
			version := [3]byte(plain[0:3])
			if version != sbtls.RealityAnnouncedVersion {
				t.Fatalf("announced %v, the patch says %v", version, sbtls.RealityAnnouncedVersion)
			}
			if version[0] < 26 || version[0] == 26 && (version[1] < 3 || version[1] == 3 && version[2] < 27) {
				t.Fatalf("announced %v: Xray v26.7.x servers refuse anything below 26.3.27", version)
			}
			if got := hex.EncodeToString(plain[8:16]); got != "0123abcd00000000" {
				t.Fatalf("short id %s", got)
			}
		})
	}
}

// captureRealityHello starts the front with a Reality upstream at a listener,
// asks for a public address through it and returns the ClientHello handshake
// message (header included) the listener was sent.
func captureRealityHello(t *testing.T, fp, pub, shortID string) []byte {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	got := make(chan []byte, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
		header := make([]byte, 5)
		if _, err := io.ReadFull(c, header); err != nil || header[0] != 0x16 {
			got <- nil
			return
		}
		record := make([]byte, binary.BigEndian.Uint16(header[3:5]))
		if _, err := io.ReadFull(c, record); err != nil {
			got <- nil
			return
		}
		got <- record
	}()

	port, _ := olcrtc.FreePort()
	p := base(ModeProxy)
	p.ProxyPort, p.ProbeListen = port, ""
	p.Upstream = Upstream{Vless: &links.VlessLine{UUID: e2eUUID, Host: "127.0.0.1", Port: ln.Addr().(*net.TCPAddr).Port, SNI: realitySNI,
		PublicKey: pub, ShortID: shortID, Fingerprint: fp, Flow: "xtls-rprx-vision", Transport: links.Transport{Kind: "tcp"}}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	f, err := Start(ctx, p)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	d, _ := proxy.SOCKS5("tcp", p.ProxyListen+":"+strconv.Itoa(port), nil, proxy.Direct)
	cd, _ := d.(proxy.ContextDialer)
	hc := &http.Client{Transport: &http.Transport{DialContext: cd.DialContext}, Timeout: 10 * time.Second}
	go func() {
		if resp, err := hc.Get(throughTunnel); err == nil {
			resp.Body.Close()
		}
	}()
	select {
	case hello := <-got:
		if len(hello) < 4 || hello[0] != 1 {
			t.Fatalf("the upstream was not sent a ClientHello: % x", hello)
		}
		return hello
	case <-time.After(15 * time.Second):
		t.Fatal("the front never dialled the upstream")
	}
	return nil
}

type keyShare struct {
	group uint16
	key   []byte
}

// helloGroupsAndShares walks a ClientHello: version, random, session id,
// cipher suites, compression, then the extensions.
func helloGroupsAndShares(t *testing.T, hello []byte) (groups []uint16, shares []keyShare) {
	t.Helper()
	b := hello[4:]
	take := func(n int) []byte {
		if n > len(b) {
			t.Fatalf("ClientHello cut short")
		}
		out := b[:n]
		b = b[n:]
		return out
	}
	take(2 + 32)
	take(int(take(1)[0]))
	take(int(binary.BigEndian.Uint16(take(2))))
	take(int(take(1)[0]))
	exts := take(int(binary.BigEndian.Uint16(take(2))))
	for len(exts) >= 4 {
		typ, n := binary.BigEndian.Uint16(exts[0:2]), int(binary.BigEndian.Uint16(exts[2:4]))
		data := exts[4 : 4+n]
		exts = exts[4+n:]
		switch typ {
		case extSupportedGroups:
			list := data[2:]
			for i := 0; i+1 < len(list); i += 2 {
				groups = append(groups, binary.BigEndian.Uint16(list[i:]))
			}
		case extKeyShare:
			list := data[2:]
			for len(list) >= 4 {
				g, kn := binary.BigEndian.Uint16(list[0:2]), int(binary.BigEndian.Uint16(list[2:4]))
				shares = append(shares, keyShare{g, list[4 : 4+kn]})
				list = list[4+kn:]
			}
		}
	}
	return groups, shares
}

// openSessionID undoes the client's seal as an Xray server does: the auth key
// is X25519 of the server's private key and the client's X25519 share (the
// standalone one, else the hybrid share's X25519 half), through HKDF-SHA256
// over the first 20 bytes of the random; AES-GCM with the last 12 as the
// nonce and the hello, its session id zeroed, as the additional data.
func openSessionID(t *testing.T, hello []byte, serverKey *ecdh.PrivateKey, shares []keyShare) []byte {
	t.Helper()
	var clientX25519 []byte
	for _, s := range shares {
		if s.group == groupX25519 && len(s.key) == 32 {
			clientX25519 = s.key
			break
		}
	}
	if clientX25519 == nil {
		for _, s := range shares {
			if s.group == groupX25519MLKEM768 && len(s.key) == 1184+32 {
				clientX25519 = s.key[1184:]
			}
		}
	}
	if clientX25519 == nil {
		t.Fatal("no X25519 key in the hello's key shares")
	}
	peer, err := ecdh.X25519().NewPublicKey(clientX25519)
	if err != nil {
		t.Fatal(err)
	}
	authKey, err := serverKey.ECDH(peer)
	if err != nil {
		t.Fatal(err)
	}
	random := hello[6:38]
	if _, err := hkdf.New(sha256.New, authKey, random[:20], []byte("REALITY")).Read(authKey); err != nil {
		t.Fatal(err)
	}
	block, _ := aes.NewCipher(authKey)
	aead, _ := cipher.NewGCM(block)
	if hello[38] != 32 {
		t.Fatalf("session id of %d bytes", hello[38])
	}
	sealed := append([]byte(nil), hello[39:71]...)
	aad := append([]byte(nil), hello...)
	clear(aad[39:71])
	plain, err := aead.Open(nil, random[20:], sealed, aad)
	if err != nil {
		t.Fatalf("the session id does not open with the server's key: %v", err)
	}
	return plain
}

func shareGroups(shares []keyShare) []uint16 {
	out := make([]uint16, 0, len(shares))
	for _, s := range shares {
		if s.group&0x0f0f != 0x0a0a { // GREASE
			out = append(out, s.group)
		}
	}
	return out
}

func indexOf(list []uint16, v uint16) int {
	for i, x := range list {
		if x == v {
			return i
		}
	}
	return -1
}

func contains(list []uint16, v uint16) bool { return indexOf(list, v) >= 0 }
