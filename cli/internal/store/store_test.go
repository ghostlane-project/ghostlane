package store

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

func TestConfigRoundTripAndPerms(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "config.yaml")
	c, err := Load(p)
	if err != nil || len(c.Subscriptions) != 0 || c.Proxy.Port != 1080 || c.Proxy.Listen != "127.0.0.1" {
		t.Fatalf("defaults: %+v %v", c, err)
	}
	c.Subscriptions = append(c.Subscriptions, Subscription{URL: "https://s/sub/a/b?c=olcbox", Title: "t", IntervalHours: 1, AddedAt: time.Unix(1, 0).UTC()})
	c.Selection = &Selection{Subscription: "https://s/sub/a/b?c=olcbox", Selector: "DE", Mode: "tun"}
	if err := Save(p, c); err != nil {
		t.Fatal(err)
	}
	st, _ := os.Stat(p)
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("perm %v", st.Mode().Perm())
	}
	back, err := Load(p)
	if err != nil || back.Selection == nil || back.Selection.Selector != "DE" || !back.Subscriptions[0].AddedAt.Equal(c.Subscriptions[0].AddedAt) {
		t.Fatalf("%+v %v", back, err)
	}
	if _, err := os.Stat(p + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("temp file left behind")
	}
}

func TestCacheAndLastGood(t *testing.T) {
	dir := t.TempDir()
	if c, err := LoadCache(dir, "https://s/sub/x"); err != nil || c != nil {
		t.Fatalf("missing cache is nil,nil: %v %v", c, err)
	}
	in := &Cache{Body: []byte("olcrtc://…"), Headers: links.Headers{Title: "T", UpdateIntervalHours: 3}, FetchedAt: time.Unix(5, 0).UTC()}
	if err := SaveCache(dir, "https://s/sub/x", in); err != nil {
		t.Fatal(err)
	}
	out, err := LoadCache(dir, "https://s/sub/x")
	if err != nil || string(out.Body) != "olcrtc://…" || out.Headers.Title != "T" || !out.FetchedAt.Equal(in.FetchedAt) {
		t.Fatalf("%+v %v", out, err)
	}
	if err := SaveLastGood(dir, map[string]string{"https://s/sub/x|DE": "abc"}); err != nil {
		t.Fatal(err)
	}
	m, err := LoadLastGood(dir)
	if err != nil || m["https://s/sub/x|DE"] != "abc" {
		t.Fatalf("%v %v", m, err)
	}
}

func TestMaskURL(t *testing.T) {
	if got := MaskURL("https://sub.x.org/sub/j7k9e/4gg96t28380ot6es?c=olcbox"); got != "https://sub.x.org/sub/j7k9e/…?c=olcbox" {
		t.Fatalf("%q", got)
	}
	if got := MaskURL("https://proofkit.org/sub/abcdef/unified"); got != "https://proofkit.org/sub/…/unified" {
		t.Fatalf("%q", got)
	}
	if got := MaskURL("https://x.org/list.txt"); got != "https://x.org/…" {
		t.Fatalf("%q", got)
	}
}
