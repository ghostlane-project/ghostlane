package links

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	neturl "net/url"
	"strings"
	"unicode"
	"unicode/utf8"
)

var errBadURL = errors.New("list: the URL cannot be parsed")

const (
	UserAgentPrefix = "Ghostlane-cli/"
	maxBody         = 4 << 20

	// deviceFieldMax is what the platform reads of each device header: 64
	// printable ASCII characters. The app sends no more either.
	deviceFieldMax = 64
	// announceMax is how much of a host's sentence is shown; the app cuts at
	// 200 characters too.
	announceMax = 200
	// titleMax is how much of a list's title is shown.
	titleMax = 200
)

// Device is what a list fetch says about this machine, the way Happ and the
// app do, in four headers: x-hwid, x-device-os, x-ver-os and x-device-model. A
// provider's device limit counts devices by the id. A field that is empty is
// not sent. With no id a host that has a limit says so, and the person is told;
// an id made up for the occasion would be a new device at every start.
type Device struct {
	ID        string
	OS        string // Linux
	OSVersion string // the kernel release
	Model     string // PC
}

func (d Device) setHeaders(h http.Header) {
	for _, f := range [...]struct{ name, value string }{
		{"x-hwid", d.ID}, {"x-device-os", d.OS}, {"x-ver-os", d.OSVersion}, {"x-device-model", d.Model},
	} {
		if v := asciiHeader(f.value); v != "" {
			h.Set(f.name, v)
		}
	}
}

// asciiHeader makes a device field what the platform reads of one: printable
// ASCII only (anything else is dropped, not replaced), at most 64 characters,
// no space at either end. The app's asciiHeader (data/device) does the same,
// and TestAsciiHeader holds the two to the same cases.
func asciiHeader(s string) string {
	b := make([]byte, 0, min(len(s), deviceFieldMax))
	for i := 0; i < len(s) && len(b) < deviceFieldMax; i++ {
		if c := s[i]; c >= ' ' && c <= '~' {
			b = append(b, c)
		}
	}
	return strings.TrimSpace(string(b))
}

// ErrDeviceRefused is a list host's refusal of this device: HTTP 200 with
// x-hwid-max-devices-reached or x-hwid-not-supported, the host's sentence in
// announce, and for a body one placeholder line, a server that goes nowhere
// named by the sentence. That line is not a list: Fetch does not read it, let
// alone return it as one. The headers the answer carried (title, refresh
// interval, usage) come back with the error.
type ErrDeviceRefused struct {
	Kind     Refusal
	Announce string // the host's sentence (base64 decoded) on one line of at most 200 characters; may be empty
}

func (e *ErrDeviceRefused) Error() string {
	return "list: device refused: " + RefusalText(e.Kind, e.Announce)
}

// RefusalText is what to tell the person about a refusal: the host's own
// sentence, cut to one line of 200 characters, else ours for the kind of
// refusal (the app's strings, refresh_device_limit_reached and
// refresh_device_not_supported). The sentence is text from the network that
// goes to a terminal and a log, so control characters in it become spaces.
// The limit's words cover a device the provider removed too: the host sends
// the same flag for both.
func RefusalText(kind Refusal, announce string) string {
	if s := announceText(announce); s != "" {
		return s
	}
	if kind == RefusalNoDevice {
		return "The provider could not read this device's id. Refresh the list; if it stays so, ask your provider."
	}
	return "This list has no place for this device. Ask your provider."
}

// announceText is a refusal's announce as it may be shown: none for "0" (the
// provider's word for no note), for one whose base64 did not decode (it still
// carries its prefix, in any case) and for one that is not UTF-8, as the app
// reads them; else the text on one line of 200 characters.
func announceText(s string) string {
	s = strings.TrimSpace(s)
	if s == "0" || !utf8.ValidString(s) {
		return ""
	}
	if _, encoded := cutBase64Prefix(s); encoded {
		return ""
	}
	return oneLine(s, announceMax)
}

// oneLine puts s on one line: every control character and every kind of white
// space becomes one space (leading and trailing ones go), a character that sets
// the direction of what follows (directional) is dropped, and what is longer
// than limit characters is cut.
func oneLine(s string, limit int) string {
	out := make([]rune, 0, min(len(s), limit+1))
	gap := false
	for _, r := range s {
		if directional(r) {
			continue
		}
		if unicode.IsSpace(r) || unicode.IsControl(r) {
			gap = len(out) > 0
			continue
		}
		if gap {
			out = append(out, ' ')
			gap = false
		}
		out = append(out, r)
		if len(out) > limit {
			break
		}
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return strings.TrimRight(string(out), " ")
}

// directional reports the format characters that change the direction text is
// drawn in, which would turn what follows them around on a terminal: the
// embeddings and overrides (U+202A to U+202E), the isolates (U+2066 to U+2069)
// and the marks (U+200E, U+200F, U+061C), with the byte order mark (U+FEFF).
// The other format characters stay: Persian writes its words with the
// zero-width non-joiner (U+200C), and an emoji's parts are joined by U+200D.
func directional(r rune) bool {
	switch {
	case r >= 0x202A && r <= 0x202E, r >= 0x2066 && r <= 0x2069:
		return true
	case r == 0x200E, r == 0x200F, r == 0x061C, r == 0xFEFF:
		return true
	}
	return false
}

// Fetch asks a list host for its list, naming dev. An answer that refuses the
// device returns no list and an *ErrDeviceRefused.
func Fetch(ctx context.Context, client *http.Client, url, userAgent string, dev Device) ([]byte, Headers, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, Headers{}, errBadURL
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "text/plain, */*")
	dev.setHeaders(req.Header)
	resp, err := client.Do(req)
	if err != nil {
		// *url.Error prints the URL, and the URL is the credential.
		var ue *neturl.Error
		if errors.As(err, &ue) {
			err = ue.Err
		}
		return nil, Headers{}, fmt.Errorf("list: fetch: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, Headers{}, fmt.Errorf("list: HTTP %d", resp.StatusCode)
	}
	headers := ParseHeaders(resp.Header)
	if headers.Refusal != RefusalNone {
		// Any 2xx says it, as the app and the iPhone's tunnel read it. Not read
		// as a list, whatever the body is. The sentence is kept to one line of
		// 200 characters, or none: the headers are stored with the list.
		headers.Announce = announceText(headers.Announce)
		return nil, headers, &ErrDeviceRefused{Kind: headers.Refusal, Announce: headers.Announce}
	}
	if resp.StatusCode != http.StatusOK {
		return nil, Headers{}, fmt.Errorf("list: HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBody+1))
	if err != nil {
		return nil, Headers{}, err
	}
	if len(body) > maxBody {
		return nil, Headers{}, fmt.Errorf("list: body over %d bytes", maxBody)
	}
	return body, headers, nil
}
