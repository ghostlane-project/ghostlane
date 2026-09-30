package daemon

import (
	"strings"
	"testing"
)

func TestScrub(t *testing.T) {
	k := strings.Repeat("ab", 32)
	in := "joined olcrtc://telemost?vp8channel@room#" + k + " from https://sub.x/sub/j7k9e/4gg96t28380ot6es?c=olcbox and https://proofkit.org/sub/abcdefghijklmnop/unified token=zzz"
	got := Scrub(in)
	for _, bad := range []string{k, "4gg96t28380ot6es", "abcdefghijklmnop", "zzz"} {
		if strings.Contains(got, bad) {
			t.Fatalf("%q leaked in %q", bad, got)
		}
	}
	if !strings.Contains(got, "/sub/j7k9e/…") || !strings.Contains(got, "/sub/…/unified") || !strings.Contains(got, "#<key>") {
		t.Fatalf("%q", got)
	}
	if got := Scrub("invalid UUID: 9f4b2c3a-1111-4222-8333-444455556666 in line"); strings.Contains(got, "9f4b2c3a") {
		t.Fatalf("a VLESS uuid is a credential: %q", got)
	}
	panel := Scrub(`refresh https://panel.example/api/v1/subscribe/TOKEN123?x=1: Get "https://panel.example/api/v1/subscribe/TOKEN123": dial tcp: timeout`)
	if strings.Contains(panel, "TOKEN123") || !strings.Contains(panel, "https://panel.example/…") {
		t.Fatalf("a non-/sub/ list path must be masked too: %q", panel)
	}
}
