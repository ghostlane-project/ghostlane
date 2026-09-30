// Package links parses the lines and lists Ghostlane accepts.
package links

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

const olcrtcPrefix = "olcrtc://"

var (
	ErrNotOlcrtc = errors.New("not an olcrtc:// line")
	ErrCrypt1    = errors.New("olcrtc://crypt1 lists need a build secret and are not supported by this version")
	errMalformed = errors.New("olcrtc line: expected provider?transport@room#key")
	errEmptyRoom = errors.New("olcrtc line: empty room")
	errBadKey    = errors.New("olcrtc line: key must be 64 hex characters")
)

// OlcrtcLine is one room: olcrtc://<provider>?<transport>[&k=v…]@<room>#<key>[%<client>][$<label>]
// (the grammar of LocationsDatasource.parseOlcRtcUri in the app).
type OlcrtcLine struct {
	Provider  string // telemost | wbstream | jitsi | salutejazz | vkcalls
	Transport string // datachannel | vp8channel | seichannel | videochannel
	Room      string
	Key       string // 64 hex characters
	Label     string // after the $, else the room
	VP8FPS    int    // 0 = engine default
	VP8Batch  int    // 0 = engine default
}

func ParseOlcrtc(line string) (*OlcrtcLine, error) {
	line = strings.TrimSpace(line)
	if !strings.HasPrefix(line, olcrtcPrefix) {
		return nil, ErrNotOlcrtc
	}
	payload := line[len(olcrtcPrefix):]
	if strings.HasPrefix(payload, "crypt1/") {
		return nil, ErrCrypt1
	}
	t := strings.IndexByte(payload, '?')
	r := indexFrom(payload, '@', t+1)
	k := indexFrom(payload, '#', r+1)
	if t <= 0 || r <= t || k <= r {
		return nil, errMalformed
	}
	keyEnd := len(payload)
	labelAt := indexFrom(payload, '$', k+1)
	if i := indexFrom(payload, '%', k+1); i >= 0 && i < keyEnd {
		keyEnd = i
	}
	if labelAt >= 0 && labelAt < keyEnd {
		keyEnd = labelAt
	}
	provider, ok := NormalizeProvider(payload[:t])
	if !ok {
		return nil, fmt.Errorf("olcrtc line: unknown carrier %q", payload[:t])
	}
	tokens := strings.Split(payload[t+1:r], "&")
	transport, ok := NormalizeTransport(tokens[0])
	if !ok {
		return nil, fmt.Errorf("olcrtc line: unknown transport %q", tokens[0])
	}
	out := &OlcrtcLine{Provider: provider, Transport: transport}
	for _, kv := range tokens[1:] {
		name, value, _ := strings.Cut(kv, "=")
		n, err := strconv.Atoi(strings.TrimSpace(value))
		if err != nil || n <= 0 {
			continue
		}
		switch strings.ToLower(strings.TrimSpace(name)) {
		case "fps", "vp8-fps":
			out.VP8FPS = n
		case "batch", "vp8-batch":
			out.VP8Batch = n
		}
	}
	out.Room = strings.TrimSpace(payload[r+1 : k])
	out.Key = strings.TrimSpace(payload[k+1 : keyEnd])
	if out.Room == "" {
		return nil, errEmptyRoom
	}
	if !isHex64(out.Key) {
		return nil, errBadKey
	}
	if labelAt >= 0 {
		out.Label = strings.TrimSpace(payload[labelAt+1:])
	}
	if out.Label == "" {
		out.Label = out.Room
	}
	return out, nil
}

func indexFrom(s string, c byte, from int) int {
	if from < 0 || from > len(s) {
		return -1
	}
	i := strings.IndexByte(s[from:], c)
	if i < 0 {
		return -1
	}
	return from + i
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, c := range s {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F') {
			return false
		}
	}
	return true
}

// NormalizeProvider mirrors LocationConfig.normalizeProvider in the app, minus its
// silent fallback: an unknown carrier is refused, not guessed.
func NormalizeProvider(s string) (string, bool) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "telemost", "yandex", "yandex_telemost":
		return "telemost", true
	case "wbstream", "wb-stream", "wb_stream", "wildberries":
		return "wbstream", true
	case "jitsi", "jitsi-meet", "jitsi_meet", "meet":
		return "jitsi", true
	case "salutejazz", "jazz", "sberjazz", "sber_jazz":
		return "salutejazz", true
	case "vkcalls", "vk", "vkcall", "vk_calls", "vk-calls":
		return "vkcalls", true
	}
	return "", false
}

// NormalizeTransport mirrors LocationConfig.transportOrNull in the app.
func NormalizeTransport(s string) (string, bool) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "datachannel", "data", "dc":
		return "datachannel", true
	case "vp8channel", "vp8", "video_vp8", "video-vp8":
		return "vp8channel", true
	case "seichannel", "sei":
		return "seichannel", true
	case "videochannel", "video":
		return "videochannel", true
	}
	return "", false
}
