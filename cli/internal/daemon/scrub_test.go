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
}
