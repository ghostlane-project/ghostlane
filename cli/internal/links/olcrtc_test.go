package links

import (
	"errors"
	"strings"
	"testing"
)

const key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func TestParseOlcrtcPartnerShapes(t *testing.T) {
	cases := []struct {
		line                      string
		provider, transport, room string
		label                     string
	}{
		{"olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/3305071026#" + key + "$🇨🇦 CA · VP8",
			"telemost", "vp8channel", "https://telemost.yandex.ru/j/3305071026", "🇨🇦 CA · VP8"},
		{"olcrtc://wbstream?vp8channel@room_z9dszi2t#" + key + "$🇨🇦 CA · VP8 · WB",
			"wbstream", "vp8channel", "room_z9dszi2t", "🇨🇦 CA · VP8 · WB"},
		{"olcrtc://salutejazz?datachannel@53a942:cb3gjji2#" + key + "$🇨🇦 CA · SJ",
			"salutejazz", "datachannel", "53a942:cb3gjji2", "🇨🇦 CA · SJ"},
		{"olcrtc://vkcalls?vp8channel@https://vk.ru/call/join/v60W-XIZsCXbDpgg#" + key + "$🇨🇦 CA · VP8",
			"vkcalls", "vp8channel", "https://vk.ru/call/join/v60W-XIZsCXbDpgg", "🇨🇦 CA · VP8"},
		// ProofKit's own list names lines "DE · olcRTC"
		{"olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/99#" + key + "$DE · olcRTC",
			"telemost", "vp8channel", "https://telemost.yandex.ru/j/99", "DE · olcRTC"},
		// no label: the room is the label
		{"olcrtc://yandex?vp8@https://telemost.yandex.ru/j/1#" + key,
			"telemost", "vp8channel", "https://telemost.yandex.ru/j/1", "https://telemost.yandex.ru/j/1"},
		// a %client segment ends the key; the label still follows the $
		{"olcrtc://telemost?vp8channel@r#" + key + "%c=1$IT · olcRTC",
			"telemost", "vp8channel", "r", "IT · olcRTC"},
	}
	for _, c := range cases {
		got, err := ParseOlcrtc(c.line)
		if err != nil {
			t.Fatalf("%s: %v", c.line, err)
		}
		if got.Provider != c.provider || got.Transport != c.transport || got.Room != c.room || got.Label != c.label || got.Key != key {
			t.Fatalf("%s:\n got %+v", c.line, got)
		}
	}
}

func TestParseOlcrtcVP8Options(t *testing.T) {
	got, err := ParseOlcrtc("olcrtc://telemost?vp8channel&fps=25&batch=6@room#" + key + "$X")
	if err != nil {
		t.Fatal(err)
	}
	if got.VP8FPS != 25 || got.VP8Batch != 6 {
		t.Fatalf("got fps=%d batch=%d", got.VP8FPS, got.VP8Batch)
	}
	got, _ = ParseOlcrtc("olcrtc://telemost?vp8channel&vp8-fps=30&vp8-batch=8@room#" + key)
	if got.VP8FPS != 30 || got.VP8Batch != 8 {
		t.Fatalf("vp8- prefixed options: got fps=%d batch=%d", got.VP8FPS, got.VP8Batch)
	}
}

func TestParseOlcrtcRefusals(t *testing.T) {
	if _, err := ParseOlcrtc("vless://x@y:1"); !errors.Is(err, ErrNotOlcrtc) {
		t.Fatalf("want ErrNotOlcrtc, got %v", err)
	}
	if _, err := ParseOlcrtc("olcrtc://crypt1/abcdef"); !errors.Is(err, ErrCrypt1) {
		t.Fatalf("want ErrCrypt1, got %v", err)
	}
	bad := []string{
		"olcrtc://telemost@room#" + key,         // no transport
		"olcrtc://telemost?vp8channel#" + key,   // no room
		"olcrtc://telemost?vp8channel@room",     // no key
		"olcrtc://telemost?vp8channel@room#abc", // short key
		"olcrtc://zoom?vp8channel@room#" + key,  // unknown carrier
		"olcrtc://telemost?h264@room#" + key,    // unknown transport
	}
	for _, b := range bad {
		if _, err := ParseOlcrtc(b); err == nil || errors.Is(err, ErrNotOlcrtc) {
			t.Fatalf("%s: want a parse error, got %v", b, err)
		}
	}
	_, err := ParseOlcrtc("olcrtc://zoom?vp8channel@room#" + key)
	if !strings.Contains(err.Error(), "zoom") {
		t.Fatalf("the error names the carrier: %v", err)
	}
}

func TestNormalizers(t *testing.T) {
	for in, want := range map[string]string{"Telemost": "telemost", "yandex_telemost": "telemost",
		"wb-stream": "wbstream", "wildberries": "wbstream", "jitsi-meet": "jitsi", "sberjazz": "salutejazz",
		"jazz": "salutejazz", "vk": "vkcalls", "vk_calls": "vkcalls"} {
		got, ok := NormalizeProvider(in)
		if !ok || got != want {
			t.Fatalf("NormalizeProvider(%q) = %q,%v", in, got, ok)
		}
	}
	if _, ok := NormalizeProvider("zoom"); ok {
		t.Fatal("zoom is not a carrier")
	}
	for in, want := range map[string]string{"vp8": "vp8channel", "video-vp8": "vp8channel", "dc": "datachannel",
		"data": "datachannel", "sei": "seichannel", "video": "videochannel", "VideoChannel": "videochannel"} {
		got, ok := NormalizeTransport(in)
		if !ok || got != want {
			t.Fatalf("NormalizeTransport(%q) = %q,%v", in, got, ok)
		}
	}
}
