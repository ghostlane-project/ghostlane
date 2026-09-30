package links

import (
	"encoding/base64"
	"net/http"
	"os"
	"strings"
	"testing"
)

func fixtureLines(t *testing.T, name string) []string {
	t.Helper()
	b, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return DecodeBody(b)
}

func TestDecodeBodyBase64AndPlain(t *testing.T) {
	plain := "olcrtc://telemost?vp8channel@r#" + key + "$DE · olcRTC\nvless://u@h:443?type=tcp#x\n"
	if got := DecodeBody([]byte(plain)); len(got) != 2 {
		t.Fatalf("plaintext: %d lines", len(got))
	}
	enc := base64.StdEncoding.EncodeToString([]byte(plain))
	wrapped := enc[:40] + "\n" + enc[40:] + "\n"
	if got := DecodeBody([]byte(wrapped)); len(got) != 2 || !strings.HasPrefix(got[1], "vless://") {
		t.Fatalf("base64 with newlines: %v", got)
	}
	unpadded := strings.TrimRight(enc, "=")
	if got := DecodeBody([]byte(unpadded)); len(got) != 2 {
		t.Fatalf("base64 without padding: %v", got)
	}
	// Review Focus 1: base64-shaped plaintext stays plaintext.
	one := "vless://abcdefabcdefabcdef@hostname:443"
	if got := DecodeBody([]byte(one)); len(got) != 1 || got[0] != one {
		t.Fatalf("a single unpadded line must not be base64-decoded: %v", got)
	}
}

func TestEntriesPartnerOlcbox(t *testing.T) {
	entries := Entries("https://sub.example/sub/a/b?c=olcbox", fixtureLines(t, "partner-olcbox.txt"))
	if len(entries) != 64 {
		t.Fatalf("%d entries", len(entries))
	}
	for _, e := range entries {
		if e.Kind != KindOlcrtc || e.Olcrtc == nil || e.Country == "" || e.ID == "" {
			t.Fatalf("entry %+v", e)
		}
	}
	groups := GroupByCountry(entries)
	if len(groups) != 16 || groups[0].Country != "CA" || len(groups[0].Entries) != 4 {
		t.Fatalf("groups: %d, first %+v", len(groups), groups[0])
	}
	want := []string{"telemost", "wbstream", "salutejazz", "vkcalls"}
	for i, e := range groups[0].Entries {
		if e.Olcrtc.Provider != want[i] {
			t.Fatalf("carrier order in a country must be the list's: %v", groups[0].Entries)
		}
	}
	ids := map[string]bool{}
	for _, e := range entries {
		if ids[e.ID] {
			t.Fatalf("duplicate id %s (labels repeat: %q)", e.ID, e.Label)
		}
		ids[e.ID] = true
	}
}

func TestEntriesPlainListIsUnsupportedForNow(t *testing.T) {
	entries := Entries("https://sub.example/sub/a/b", fixtureLines(t, "partner-plain.txt"))
	if len(entries) != 17 {
		t.Fatalf("%d entries", len(entries))
	}
	kinds := map[Kind]int{}
	for _, e := range entries {
		kinds[e.Kind]++
		if e.Problem == "" {
			t.Fatalf("a line that cannot be connected says why: %+v", e)
		}
	}
	if kinds[KindHysteria2] != 8 || kinds[KindVless] != 9 {
		t.Fatalf("kinds %v", kinds)
	}
}

func TestSelect(t *testing.T) {
	entries := Entries("u", fixtureLines(t, "partner-olcbox.txt"))
	got, err := Select(entries, "de")
	if err != nil || len(got) != 4 || got[0].Country != "DE" {
		t.Fatalf("country: %v %v", got, err)
	}
	got, err = Select(entries, "🇩🇪 DE · SJ")
	if err != nil || len(got) != 1 || got[0].Olcrtc.Provider != "salutejazz" {
		t.Fatalf("label: %v %v", got, err)
	}
	got, err = Select(entries, "6")
	if err != nil || len(got) != 1 || got[0].ID != entries[5].ID {
		t.Fatalf("index: %v %v", got, err)
	}
	// Review Focus 2: no guessing.
	if _, err = Select(entries, "D"); err == nil || !strings.Contains(err.Error(), "CA") {
		t.Fatalf("an unknown selector fails and lists the countries: %v", err)
	}
	if _, err = Select(entries, "99"); err == nil {
		t.Fatal("index out of range")
	}
	dup := Entries("u", []string{
		"olcrtc://telemost?vp8channel@a#" + key + "$VPN MOBL",
		"olcrtc://telemost?vp8channel@b#" + key + "$VPN MOBL",
	})
	if dup[0].ID == dup[1].ID {
		t.Fatal("same label twice must still get distinct ids")
	}
	if _, err = Select(dup, "VPN MOBL"); err == nil || !strings.Contains(err.Error(), "index") {
		t.Fatalf("an ambiguous label asks for the index: %v", err)
	}
}

func TestParseHeaders(t *testing.T) {
	h := http.Header{}
	h.Set("Profile-Title", "base64:QFhyYXl2bGVzc3BheW1lbnRib3Q=")
	h.Set("Profile-Update-Interval", "1")
	h.Set("Subscription-Userinfo", "upload=0; download=12; total=107373108658176; expire=1854106383")
	h.Set("Support-Url", "https://t.me/x")
	h.Set("Announce", "base64:aGVsbG8=")
	got := ParseHeaders(h)
	if got.Title != "@Xrayvlesspaymentbot" || got.UpdateIntervalHours != 1 || got.SupportURL != "https://t.me/x" || got.Announce != "hello" {
		t.Fatalf("%+v", got)
	}
	if got.UserInfo == nil || got.UserInfo.Download != 12 || got.UserInfo.Total != 107373108658176 || got.UserInfo.Expire != 1854106383 {
		t.Fatalf("%+v", got.UserInfo)
	}
	if def := ParseHeaders(http.Header{}); def.UpdateIntervalHours != 24 || def.UserInfo != nil {
		t.Fatalf("defaults: %+v", def)
	}
}
