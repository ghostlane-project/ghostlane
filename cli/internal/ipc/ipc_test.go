package ipc

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
)

func TestRoundTrip(t *testing.T) {
	sock := filepath.Join(t.TempDir(), "s.sock")
	l, err := Listen(sock, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		_ = Serve(ctx, l, func(_ context.Context, r Request) Response {
			if r.Verb == "status" {
				return Response{OK: true, Status: &Status{State: "up", Selector: r.Selector}}
			}
			return Fail("bad_verb", "unknown verb "+r.Verb)
		})
	}()
	resp, err := Call(ctx, sock, Request{Verb: "status", Selector: "DE"})
	if err != nil || !resp.OK || resp.Status.State != "up" || resp.Status.Selector != "DE" {
		t.Fatalf("%+v %v", resp, err)
	}
	resp, err = Call(ctx, sock, Request{Verb: "nope"})
	if err != nil || resp.OK || resp.Error != "bad_verb" {
		t.Fatalf("%+v %v", resp, err)
	}
	_, err = Call(ctx, filepath.Join(t.TempDir(), "missing.sock"), Request{Verb: "status"})
	if !errors.Is(err, ErrDaemonDown) {
		t.Fatalf("want ErrDaemonDown, got %v", err)
	}
}
