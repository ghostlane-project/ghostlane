// Package xray runs Xray-core as a library for the one transport sing-box
// cannot speak, XHTTP, behind a loopback SOCKS the front dials.
package xray

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all" // registers every Xray feature
)

type Session struct {
	inst *core.Instance
	addr string
	user string
	pass string
	mu   sync.Mutex
	done bool
}

// Start builds and starts an Xray instance for one xhttp line. A cancelled ctx
// before the start completes closes the instance again.
func Start(ctx context.Context, p Params) (*Session, error) {
	cfg, err := BuildConfig(p)
	if err != nil {
		return nil, err
	}
	pb, err := serial.LoadJSONConfig(bytes.NewReader(cfg))
	if err != nil {
		return nil, fmt.Errorf("xray config: %w", err)
	}
	inst, err := core.New(pb)
	if err != nil {
		return nil, fmt.Errorf("xray: %w", err)
	}
	if err := inst.Start(); err != nil {
		return nil, fmt.Errorf("xray start: %w", err)
	}
	if ctx.Err() != nil {
		_ = inst.Close()
		return nil, ctx.Err()
	}
	return &Session{inst: inst, addr: net.JoinHostPort(p.SocksHost, strconv.Itoa(p.SocksPort)), user: p.SocksUser, pass: p.SocksPass}, nil
}

func (s *Session) SocksAddr() string             { return s.addr }
func (s *Session) Credentials() (string, string) { return s.user, s.pass }

func (s *Session) State() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.done {
		return "stopped"
	}
	return "running"
}

func (s *Session) Stop(time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.done {
		return nil
	}
	s.done = true
	return s.inst.Close()
}
