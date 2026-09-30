// Package ipc is the newline-delimited JSON protocol between `ghostlane run` and
// the other subcommands, over a unix socket.
package ipc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

const DefaultSocketPath = "/run/ghostlane/ghostlane.sock"

var ErrDaemonDown = errors.New("the ghostlane daemon is not running (systemctl start ghostlane, or run `ghostlane run` where there is no systemd)")

type Request struct {
	Verb         string `json:"verb"`
	Source       string `json:"source,omitempty"`
	Selector     string `json:"selector,omitempty"`
	Mode         string `json:"mode,omitempty"`
	Subscription string `json:"subscription,omitempty"`
}

type EntryView struct {
	Index     int    `json:"index"`
	ID        string `json:"id"`
	Label     string `json:"label"`
	Country   string `json:"country,omitempty"`
	Kind      string `json:"kind"`
	Carrier   string `json:"carrier,omitempty"`
	Transport string `json:"transport,omitempty"`
	Problem   string `json:"problem,omitempty"`
}

type ProxyView struct {
	Socks string `json:"socks"`
	HTTP  string `json:"http"`
}

type SubscriptionView struct {
	URL           string          `json:"url"` // masked
	Title         string          `json:"title,omitempty"`
	IntervalHours int             `json:"interval_hours"`
	FetchedAt     string          `json:"fetched_at,omitempty"`
	NextRefresh   string          `json:"next_refresh,omitempty"`
	UserInfo      *links.UserInfo `json:"userinfo,omitempty"`
	Error         string          `json:"error,omitempty"`
}

type Status struct {
	State         string             `json:"state"` // idle | connecting | up | failed
	Mode          string             `json:"mode,omitempty"`
	Selector      string             `json:"selector,omitempty"`
	Since         string             `json:"since,omitempty"`
	LastError     string             `json:"last_error,omitempty"`
	Line          *EntryView         `json:"line,omitempty"`
	Proxy         *ProxyView         `json:"proxy,omitempty"`
	Subscriptions []SubscriptionView `json:"subscriptions,omitempty"`
}

type VersionInfo struct {
	Version string `json:"version"`
	Engine  string `json:"engine"`
	SingBox string `json:"singbox"`
}

type Response struct {
	OK            bool               `json:"ok"`
	Error         string             `json:"error,omitempty"`
	Message       string             `json:"message,omitempty"`
	Status        *Status            `json:"status,omitempty"`
	Entries       []EntryView        `json:"entries,omitempty"`
	Subscriptions []SubscriptionView `json:"subscriptions,omitempty"`
	Version       *VersionInfo       `json:"version,omitempty"`
}

func Fail(code, msg string) Response { return Response{Error: code, Message: msg} }

type Handler func(context.Context, Request) Response

func Listen(path string, mode os.FileMode) (net.Listener, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return nil, err
	}
	_ = os.Remove(path)
	l, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, mode); err != nil {
		l.Close()
		return nil, err
	}
	return l, nil
}

// Serve answers one request per connection until ctx ends.
func Serve(ctx context.Context, l net.Listener, h Handler) error {
	go func() {
		<-ctx.Done()
		l.Close()
	}()
	for {
		conn, err := l.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go func() {
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(2 * time.Minute))
			var req Request
			if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&req); err != nil {
				_ = json.NewEncoder(conn).Encode(Fail("bad_request", err.Error()))
				return
			}
			_ = json.NewEncoder(conn).Encode(h(ctx, req))
		}()
	}
}

func Call(ctx context.Context, path string, req Request) (Response, error) {
	d := net.Dialer{Timeout: 3 * time.Second}
	conn, err := d.DialContext(ctx, "unix", path)
	if err != nil {
		return Response{}, fmt.Errorf("%w: %w", ErrDaemonDown, err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Minute))
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return Response{}, err
	}
	var resp Response
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&resp); err != nil {
		return Response{}, fmt.Errorf("daemon answered nothing: %w", err)
	}
	return resp, nil
}
