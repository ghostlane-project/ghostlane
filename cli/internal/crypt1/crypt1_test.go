package crypt1

import (
	"encoding/base64"
	"strings"
	"testing"

	"github.com/ghostlane-project/ghostlane/cli/internal/crypt1/crypt1test"
)

func TestRoundTrip(t *testing.T) {
	master := crypt1test.Master()
	for _, plain := range []string{"https://proofkit.org/sub/tok/unified", "olcrtc://a?vp8channel@r#k$x\nolcrtc://b?vp8channel@r#k$y\n", "", strings.Repeat("z", 1000)} {
		blob := crypt1test.Encrypt(master, []byte(plain))
		got, ok := Decrypt(master, blob)
		if !ok || string(got) != plain {
			t.Fatalf("%q: ok=%v got=%q", plain, ok, got)
		}
	}
}

func TestRefusals(t *testing.T) {
	master := crypt1test.Master()
	blob := crypt1test.Encrypt(master, []byte("hello"))
	raw, _ := base64.RawURLEncoding.DecodeString(blob)
	// bad MAC
	tampered := append([]byte{}, raw...)
	tampered[len(tampered)-1] ^= 1
	if _, ok := Decrypt(master, base64.RawURLEncoding.EncodeToString(tampered)); ok {
		t.Fatal("tampered MAC accepted")
	}
	// tampered ciphertext (MAC catches it)
	tampered = append([]byte{}, raw...)
	tampered[20] ^= 1
	if _, ok := Decrypt(master, base64.RawURLEncoding.EncodeToString(tampered)); ok {
		t.Fatal("tampered ciphertext accepted")
	}
	// wrong key
	other := master
	other[0] ^= 1
	if _, ok := Decrypt(other, blob); ok {
		t.Fatal("wrong key accepted")
	}
	// too short, bad base64
	for _, bad := range []string{"", "abc", base64.RawURLEncoding.EncodeToString(raw[:40]), "!!!not-base64!!!", strings.Repeat("A", 100)} {
		if _, ok := Decrypt(master, bad); ok {
			t.Fatalf("%q accepted", bad)
		}
	}
	// a padded standard-base64 blob is accepted too
	if _, ok := Decrypt(master, base64.StdEncoding.EncodeToString(raw)); !ok {
		t.Fatal("standard base64 with padding refused")
	}
}

func TestParseKey(t *testing.T) {
	master := crypt1test.Master()
	for _, enc := range []string{
		base64.StdEncoding.EncodeToString(master[:]),
		base64.RawStdEncoding.EncodeToString(master[:]),
		base64.URLEncoding.EncodeToString(master[:]),
		base64.RawURLEncoding.EncodeToString(master[:]),
	} {
		k, ok := ParseKey(enc)
		if !ok || k != master {
			t.Fatalf("%q: %v", enc, ok)
		}
	}
	for _, bad := range []string{"", "short", base64.StdEncoding.EncodeToString(master[:31]), "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"} {
		if _, ok := ParseKey(bad); ok {
			t.Fatalf("%q accepted (64 hex is the coordinator's form, not the client's)", bad)
		}
	}
}

func TestLinkPayload(t *testing.T) {
	master := crypt1test.Master()
	link := LinkPrefix + crypt1test.Encrypt(master, []byte("https://x/sub/t/unified"))
	if !IsLink(link) || IsLink("olcrtc://telemost?vp8channel@r#k") {
		t.Fatal("IsLink")
	}
	got, ok := DecryptLink(master, link)
	if !ok || string(got) != "https://x/sub/t/unified" {
		t.Fatalf("%q %v", got, ok)
	}
}
