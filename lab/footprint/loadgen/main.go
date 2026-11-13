// Command loadgen is the lab's traffic and key tool. It uses the standard
// library only, so the same source builds for the runner and, as a static
// linux/amd64 binary, runs unchanged under `adb shell` on an x86_64 emulator.
//
//	loadgen serve      -listen 127.0.0.1:18080        the page: GET /bytes?n=N, POST /sink
//	loadgen tlsdest    -listen 127.0.0.1:18443        a TLS 1.3 + h2 site for Reality to mirror
//	loadgen get        -url U [-socks A] [-expect N]  download, prints one JSON line
//	loadgen put        -url U [-socks A] -bytes N     upload, prints one JSON line
//	loadgen burst      -op get|put -n K -each N ...   K transfers at once, one connection each
//	loadgen x25519                                   a Reality key pair, as JSON
//	loadgen selfsigned -cert F -key F -name N        a certificate for the Hysteria2 server
//
// Nothing here is a core: it only feeds them. Transfers go to
// http://203.0.113.10/ (TEST-NET-3), an address with no route of its own: the
// lab's servers send every destination to the page (freedom "redirect"), so a
// byte that arrives proves it crossed the core and the server.
package main

import (
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"strconv"
	"time"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: loadgen serve|tlsdest|get|put|x25519|selfsigned [flags]")
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "serve":
		err = serve(os.Args[2:])
	case "tlsdest":
		err = tlsDest(os.Args[2:])
	case "get":
		os.Exit(transfer("get", os.Args[2:]))
	case "put":
		os.Exit(transfer("put", os.Args[2:]))
	case "burst":
		os.Exit(burst(os.Args[2:]))
	case "x25519":
		err = x25519()
	case "selfsigned":
		err = selfSigned(os.Args[2:])
	default:
		err = fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "loadgen:", err)
		os.Exit(1)
	}
}

// pattern is what the page sends and what an upload carries: not zeros, so
// nothing on the way could make the bytes cheaper than they are.
var pattern = func() []byte {
	b := make([]byte, 1<<20)
	var x uint32 = 2463534242
	for i := range b {
		x ^= x << 13
		x ^= x >> 17
		x ^= x << 5
		b[i] = byte(x)
	}
	return b
}()

func serve(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:18080", "address to listen on")
	_ = fs.Parse(args)
	mux := http.NewServeMux()
	mux.HandleFunc("/bytes", func(w http.ResponseWriter, r *http.Request) {
		n, err := strconv.ParseInt(r.URL.Query().Get("n"), 10, 64)
		if err != nil || n < 0 {
			http.Error(w, "n", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", strconv.FormatInt(n, 10))
		for n > 0 {
			chunk := pattern
			if int64(len(chunk)) > n {
				chunk = chunk[:n]
			}
			written, err := w.Write(chunk)
			if err != nil {
				return
			}
			n -= int64(written)
		}
	})
	mux.HandleFunc("/sink", func(w http.ResponseWriter, r *http.Request) {
		n, _ := io.Copy(io.Discard, r.Body)
		_, _ = io.WriteString(w, strconv.FormatInt(n, 10))
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, "ok")
	})
	srv := &http.Server{Addr: *listen, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	fmt.Fprintln(os.Stderr, "loadgen: page on", *listen)
	return srv.ListenAndServe()
}

// tlsDest answers like a real site a Reality server can mirror: TLS 1.3 with
// h2 offered and an RSA 2048 certificate, larger than the server's own.
// Current REALITY pads its records to the target's, and a target whose
// encrypted extensions are shorter than the server's fails every handshake
// (the CLI's end-to-end, cli/internal/engine/singbox/xray_e2e_test.go, found it).
func tlsDest(args []string) error {
	fs := flag.NewFlagSet("tlsdest", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:18443", "address to listen on")
	_ = fs.Parse(args)
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: "reality.lab.test", Organization: []string{"Ghostlane footprint lab"}},
		DNSNames:     []string{"reality.lab.test", "xhttp.lab.test", "localhost"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(48 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return err
	}
	cert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
	srv := &http.Server{
		Addr: *listen,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_, _ = io.WriteString(w, "camouflage")
		}),
		TLSConfig:         &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2", "http/1.1"}},
		ReadHeaderTimeout: 10 * time.Second,
	}
	fmt.Fprintln(os.Stderr, "loadgen: tls target on", *listen)
	return srv.ListenAndServeTLS("", "")
}

type result struct {
	Op      string  `json:"op"`
	URL     string  `json:"url"`
	Via     string  `json:"via"`
	Bytes   int64   `json:"bytes"`
	Seconds float64 `json:"seconds"`
	TTFB    float64 `json:"ttfb_s"`
	Mbps    float64 `json:"mbps"`
	Start   int64   `json:"start_ms"`
	End     int64   `json:"end_ms"`
	OK      bool    `json:"ok"`
	Error   string  `json:"error,omitempty"`
	// burst only: transfers started and transfers that finished whole.
	Flows   int `json:"flows,omitempty"`
	OKFlows int `json:"ok_flows,omitempty"`
}

// burst starts -n transfers at the same moment, each on a connection of its
// own (keep-alives off, so each is its own SOCKS CONNECT and its own
// connection through the core and the server), the way a speed test or a
// page load opens many. A single transfer never shows what each connection
// costs a core: its goroutines, stacks and buffers. Bytes, Seconds and Mbps
// are the aggregate; TTFB is the slowest first byte; OK only when every
// transfer finished whole.
func burst(args []string) int {
	fs := flag.NewFlagSet("burst", flag.ExitOnError)
	op := fs.String("op", "get", "get | put")
	target := fs.String("url", "", "get: a /bytes?n= URL; put: the /sink URL")
	socks := fs.String("socks", "", "SOCKS5 server to go through (host:port); empty dials directly")
	user := fs.String("user", "", "SOCKS5 user, when the core asks for one")
	pass := fs.String("pass", "", "SOCKS5 password")
	flows := fs.Int("n", 32, "transfers at once")
	each := fs.Int64("each", 4_000_000, "bytes per transfer: get expects this body, put sends this many")
	timeout := fs.Duration("timeout", 2*time.Minute, "give up after this long")
	_ = fs.Parse(args)

	res := result{Op: "burst-" + *op, URL: *target, Via: "direct", Flows: *flows}
	if (*op != "get" && *op != "put") || *flows < 1 || *each < 0 || *target == "" {
		res.Error = "burst: -op get|put, -n >= 1, -each >= 0 and -url are required"
		out, _ := json.Marshal(res)
		fmt.Println(string(out))
		return 2
	}
	dialer := &net.Dialer{Timeout: 15 * time.Second}
	tr := &http.Transport{
		Proxy:              nil,
		DisableCompression: true,
		DisableKeepAlives:  true,
		DialContext:        dialer.DialContext,
	}
	if *socks != "" {
		res.Via = "socks5://" + *socks
		tr.DialContext = func(ctx context.Context, _, addr string) (net.Conn, error) {
			return socksDial(ctx, *socks, *user, *pass, addr)
		}
	}
	client := &http.Client{Transport: tr, Timeout: *timeout}

	parts := make([]result, *flows)
	errs := make([]error, *flows)
	start := make(chan struct{})
	done := make(chan int, *flows)
	for i := 0; i < *flows; i++ {
		go func(i int) {
			<-start
			begin := time.Now()
			if *op == "get" {
				errs[i] = doGet(client, *target, &parts[i], begin)
				if errs[i] == nil && parts[i].Bytes != *each {
					errs[i] = fmt.Errorf("got %d bytes, expected %d", parts[i].Bytes, *each)
				}
			} else {
				errs[i] = doPut(client, *target, *each, &parts[i], begin)
			}
			done <- i
		}(i)
	}
	begin := time.Now()
	res.Start = begin.UnixMilli()
	close(start)
	for i := 0; i < *flows; i++ {
		<-done
	}
	end := time.Now()
	res.End = end.UnixMilli()
	res.Seconds = end.Sub(begin).Seconds()
	for i := range parts {
		res.Bytes += parts[i].Bytes
		if parts[i].TTFB > res.TTFB {
			res.TTFB = parts[i].TTFB
		}
		if errs[i] == nil {
			res.OKFlows++
		} else if res.Error == "" {
			res.Error = fmt.Sprintf("transfer %d: %v", i, errs[i])
		}
	}
	if res.Seconds > 0 {
		res.Mbps = float64(res.Bytes) * 8 / res.Seconds / 1e6
	}
	res.OK = res.OKFlows == *flows
	if !res.OK && res.Error != "" {
		res.Error = fmt.Sprintf("%d of %d transfers failed; first: %s", *flows-res.OKFlows, *flows, res.Error)
	}
	tr.CloseIdleConnections()
	out, _ := json.Marshal(res)
	fmt.Println(string(out))
	if !res.OK {
		return 1
	}
	return 0
}

func transfer(op string, args []string) int {
	fs := flag.NewFlagSet(op, flag.ExitOnError)
	target := fs.String("url", "http://203.0.113.10/bytes?n=1048576", "what to fetch or post to")
	socks := fs.String("socks", "", "SOCKS5 server to go through (host:port); empty dials directly")
	user := fs.String("user", "", "SOCKS5 user, when the core asks for one")
	pass := fs.String("pass", "", "SOCKS5 password")
	expect := fs.Int64("expect", -1, "get: bytes the body must have")
	size := fs.Int64("bytes", 1<<20, "put: bytes to send")
	timeout := fs.Duration("timeout", 5*time.Minute, "give up after this long")
	_ = fs.Parse(args)

	dialer := &net.Dialer{Timeout: 15 * time.Second}
	tr := &http.Transport{
		Proxy:               nil,
		DisableCompression:  true,
		MaxIdleConnsPerHost: 1,
		DialContext:         dialer.DialContext,
	}
	via := "direct"
	if *socks != "" {
		via = "socks5://" + *socks
		tr.DialContext = func(ctx context.Context, _, addr string) (net.Conn, error) {
			return socksDial(ctx, *socks, *user, *pass, addr)
		}
	}
	client := &http.Client{Transport: tr, Timeout: *timeout}
	res := result{Op: op, URL: *target, Via: via}

	begin := time.Now()
	res.Start = begin.UnixMilli()
	var err error
	switch op {
	case "get":
		err = doGet(client, *target, &res, begin)
		if err == nil && *expect >= 0 && res.Bytes != *expect {
			err = fmt.Errorf("got %d bytes, expected %d", res.Bytes, *expect)
		}
	case "put":
		err = doPut(client, *target, *size, &res, begin)
	}
	end := time.Now()
	res.End = end.UnixMilli()
	res.Seconds = end.Sub(begin).Seconds()
	if res.Seconds > 0 {
		res.Mbps = float64(res.Bytes) * 8 / res.Seconds / 1e6
	}
	res.OK = err == nil
	if err != nil {
		res.Error = err.Error()
	}
	out, _ := json.Marshal(res)
	fmt.Println(string(out))
	if !res.OK {
		return 1
	}
	return 0
}

func doGet(client *http.Client, target string, res *result, begin time.Time) error {
	resp, err := client.Get(target)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	res.TTFB = time.Since(begin).Seconds()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %d", resp.StatusCode)
	}
	n, err := io.Copy(io.Discard, resp.Body)
	res.Bytes = n
	return err
}

type patternReader struct{ left int64 }

func (p *patternReader) Read(b []byte) (int, error) {
	if p.left <= 0 {
		return 0, io.EOF
	}
	n := copy(b, pattern)
	if int64(n) > p.left {
		n = int(p.left)
	}
	p.left -= int64(n)
	return n, nil
}

func doPut(client *http.Client, target string, size int64, res *result, begin time.Time) error {
	req, err := http.NewRequest(http.MethodPost, target, &patternReader{left: size})
	if err != nil {
		return err
	}
	req.ContentLength = size
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	res.TTFB = time.Since(begin).Seconds()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64))
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %d", resp.StatusCode)
	}
	got, err := strconv.ParseInt(string(body), 10, 64)
	if err != nil {
		return fmt.Errorf("the page answered %q, not a byte count", body)
	}
	res.Bytes = got
	if got != size {
		return fmt.Errorf("the page received %d of %d bytes", got, size)
	}
	return nil
}

// socksDial is a SOCKS5 CONNECT (RFC 1928, with RFC 1929 user/password when
// a user is given), enough for the cores' socks inbounds.
func socksDial(ctx context.Context, server, user, pass, target string) (net.Conn, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", server)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	fail := func(err error) (net.Conn, error) {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 %s: %w", server, err)
	}
	method := byte(0x00)
	if user != "" {
		method = 0x02
	}
	if _, err := conn.Write([]byte{5, 1, method}); err != nil {
		return fail(err)
	}
	reply := make([]byte, 2)
	if _, err := io.ReadFull(conn, reply); err != nil {
		return fail(err)
	}
	if reply[0] != 5 || reply[1] != method {
		return fail(fmt.Errorf("server chose method %d", reply[1]))
	}
	if method == 0x02 {
		auth := []byte{1, byte(len(user))}
		auth = append(auth, user...)
		auth = append(auth, byte(len(pass)))
		auth = append(auth, pass...)
		if _, err := conn.Write(auth); err != nil {
			return fail(err)
		}
		if _, err := io.ReadFull(conn, reply); err != nil {
			return fail(err)
		}
		if reply[1] != 0 {
			return fail(errors.New("login refused"))
		}
	}
	host, portText, err := net.SplitHostPort(target)
	if err != nil {
		return fail(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		return fail(err)
	}
	req := []byte{5, 1, 0}
	if ip := net.ParseIP(host); ip != nil && ip.To4() != nil {
		req = append(req, 1)
		req = append(req, ip.To4()...)
	} else if ip != nil {
		req = append(req, 4)
		req = append(req, ip.To16()...)
	} else {
		req = append(req, 3, byte(len(host)))
		req = append(req, host...)
	}
	req = binary.BigEndian.AppendUint16(req, uint16(port))
	if _, err := conn.Write(req); err != nil {
		return fail(err)
	}
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		return fail(err)
	}
	if head[1] != 0 {
		return fail(fmt.Errorf("connect refused, reply %d", head[1]))
	}
	var skip int
	switch head[3] {
	case 1:
		skip = 4
	case 4:
		skip = 16
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return fail(err)
		}
		skip = int(l[0])
	default:
		return fail(fmt.Errorf("address type %d", head[3]))
	}
	if _, err := io.ReadFull(conn, make([]byte, skip+2)); err != nil {
		return fail(err)
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

func x25519() error {
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	out, _ := json.Marshal(map[string]string{
		"private": base64.RawURLEncoding.EncodeToString(key.Bytes()),
		"public":  base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()),
	})
	fmt.Println(string(out))
	return nil
}

func selfSigned(args []string) error {
	fs := flag.NewFlagSet("selfsigned", flag.ExitOnError)
	certPath := fs.String("cert", "cert.pem", "certificate out")
	keyPath := fs.String("key", "key.pem", "key out")
	name := fs.String("name", "hy2.lab.test", "DNS name")
	_ = fs.Parse(args)
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: *name},
		DNSNames:     []string{*name},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(48 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return err
	}
	kb, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	if err := os.WriteFile(*certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600); err != nil {
		return err
	}
	return os.WriteFile(*keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb}), 0o600)
}
