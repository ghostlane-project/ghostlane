// Package olcrtc drives the engine the apps use, mobile.Runtime, as a plain Go
// package: one Runtime per connection.
package olcrtc

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/openlibrecommunity/olcrtc/mobile"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type Runtime interface {
	SetProvider(string) error
	SetTransport(string) error
	SetRoom(string) error
	SetKey(string) error
	SetDNS(string) error
	SetSocksListenHost(string) error
	SetSocksPort(int) error
	SetSocksCredentials(string, string) error
	SetUDP(bool)
	SetDirectRules(string) error
	SetDeviceIDPath(string)
	SetVP8Options(int, int) error
	Start() error
	WaitReady(int) error
	Stop(int) error
	State() string
}

// NewRuntime is swapped by tests.
var NewRuntime = func() Runtime { return mobile.New() }

// PrivateDirectRules keeps LAN, link-local and CGNAT traffic off the tunnel,
// in the engine's route.direct syntax.
const PrivateDirectRules = "10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n100.64.0.0/10\n169.254.0.0/16\n127.0.0.0/8\nfc00::/7\nfe80::/10\n"

type Params struct {
	Line         links.OlcrtcLine
	SocksHost    string
	SocksPort    int
	SocksUser    string
	SocksPass    string
	DNS          string
	DirectRules  string
	DeviceIDPath string
	ReadyTimeout time.Duration // 0 = 60 s
}

const defaultReadyTimeout = 60 * time.Second

type Session struct {
	rt   Runtime
	addr string
	user string
	pass string
}

// Start applies the parameters, starts the runtime and waits for its SOCKS to
// serve. A cancelled ctx stops the runtime and returns at once: a person who
// changes their mind does not wait out the ready timeout.
func Start(ctx context.Context, p Params) (*Session, error) {
	readyTimeout := p.ReadyTimeout
	if readyTimeout <= 0 {
		readyTimeout = defaultReadyTimeout
	}
	rt := NewRuntime()
	steps := []struct {
		name string
		fn   func() error
	}{
		{"provider", func() error { return rt.SetProvider(p.Line.Provider) }},
		{"transport", func() error { return rt.SetTransport(p.Line.Transport) }},
		{"room", func() error { return rt.SetRoom(p.Line.Room) }},
		{"key", func() error { return rt.SetKey(p.Line.Key) }},
		{"vp8", func() error {
			if p.Line.VP8FPS == 0 && p.Line.VP8Batch == 0 {
				return nil
			}
			return rt.SetVP8Options(p.Line.VP8FPS, p.Line.VP8Batch)
		}},
		{"dns", func() error { return rt.SetDNS(p.DNS) }},
		{"socks host", func() error { return rt.SetSocksListenHost(p.SocksHost) }},
		{"socks port", func() error { return rt.SetSocksPort(p.SocksPort) }},
		{"socks credentials", func() error { return rt.SetSocksCredentials(p.SocksUser, p.SocksPass) }},
		{"udp", func() error { rt.SetUDP(true); return nil }},
		{"direct rules", func() error { return rt.SetDirectRules(p.DirectRules) }},
		{"device id", func() error { rt.SetDeviceIDPath(p.DeviceIDPath); return nil }},
	}
	for _, s := range steps {
		if err := s.fn(); err != nil {
			return nil, fmt.Errorf("engine %s: %w", s.name, err)
		}
	}
	if err := rt.Start(); err != nil {
		return nil, fmt.Errorf("engine start: %w", err)
	}
	ready := make(chan error, 1)
	go func() { ready <- rt.WaitReady(int(readyTimeout / time.Millisecond)) }()
	select {
	case err := <-ready:
		if err != nil {
			_ = rt.Stop(5000)
			return nil, fmt.Errorf("engine not ready within %s: %w", readyTimeout, err)
		}
	case <-ctx.Done():
		_ = rt.Stop(5000)
		return nil, fmt.Errorf("engine start cancelled: %w", ctx.Err())
	}
	return &Session{rt: rt, addr: net.JoinHostPort(p.SocksHost, strconv.Itoa(p.SocksPort)), user: p.SocksUser, pass: p.SocksPass}, nil
}

func (s *Session) SocksAddr() string             { return s.addr }
func (s *Session) Credentials() (string, string) { return s.user, s.pass }
func (s *Session) State() string                 { return s.rt.State() }
func (s *Session) Stop(timeout time.Duration) error {
	return s.rt.Stop(int(timeout / time.Millisecond))
}

func FreePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

func RandomCredentials() (string, string) {
	u := make([]byte, 6)
	p := make([]byte, 12)
	_, _ = rand.Read(u)
	_, _ = rand.Read(p)
	return "gl" + hex.EncodeToString(u), hex.EncodeToString(p)
}

// HostResolvers reads the nameserver lines of a resolv.conf and appends a
// public operator, in the "host:port,host:port" shape SetDNS takes.
func HostResolvers(resolvConf string) string {
	var out []string
	if f, err := os.Open(resolvConf); err == nil {
		defer f.Close()
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			fields := strings.Fields(sc.Text())
			if len(fields) >= 2 && fields[0] == "nameserver" {
				if ip := net.ParseIP(fields[1]); ip != nil {
					out = append(out, net.JoinHostPort(ip.String(), "53"))
				}
			}
		}
	}
	out = append(out, "1.1.1.1:53")
	return strings.Join(out, ",")
}

type logSink struct{ f func(string) }

func (l logSink) WriteLog(msg string) { l.f(msg) }

// SetLogSink routes the engine's log lines to f (the daemon scrubs and forwards).
func SetLogSink(f func(string)) { mobile.SetLogWriter(logSink{f}) }
