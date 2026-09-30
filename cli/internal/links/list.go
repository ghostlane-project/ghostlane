package links

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"unicode/utf8"
)

type Kind int

const (
	KindUnsupported Kind = iota
	KindOlcrtc
	KindVless
	KindHysteria2
)

func (k Kind) String() string {
	switch k {
	case KindOlcrtc:
		return "olcrtc"
	case KindVless:
		return "vless"
	case KindHysteria2:
		return "hysteria2"
	default:
		return "unsupported"
	}
}

type Entry struct {
	ID      string
	Label   string
	Country string // "" when the label names none
	Kind    Kind
	Raw     string
	Olcrtc  *OlcrtcLine
	Vless   *VlessLine
	Hy2     *Hy2Line
	Problem string // why the line cannot be connected, for `list`
}

// Connectable says whether this version can connect the line: an olcRTC room,
// a VLESS line over tcp (Reality) or xhttp, or a Hysteria2 line, parsed whole.
func (e Entry) Connectable() bool {
	return e.Problem == "" && (e.Olcrtc != nil || e.Vless != nil || e.Hy2 != nil)
}

// DecodeBody turns a subscription body into lines: base64 (whole body, wrapped or
// unpadded) when the decode yields text with a scheme in it, else the text itself.
func DecodeBody(body []byte) []string { return DecodeBodyWith(body, nil) }

// Decryptor decrypts an encrypted (crypt1) body or link blob; false = not one.
type Decryptor func(blob string) ([]byte, bool)

// DecodeBodyWith is DecodeBody with a decryptor for an encrypted body: a body
// with no scheme in it that the decryptor verifies (its MAC, so a base64 list
// never passes) is replaced by the decrypted text. A plaintext body is never
// handed to it.
func DecodeBodyWith(body []byte, decrypt Decryptor) []string {
	text := string(body)
	compact := strings.Map(func(r rune) rune {
		if r == '\n' || r == '\r' || r == ' ' || r == '\t' {
			return -1
		}
		return r
	}, text)
	switch {
	case strings.Contains(text, "://"):
	case decrypt != nil && tryDecrypt(compact, decrypt, &text):
	default:
		if decoded, ok := tryBase64(compact); ok && strings.Contains(decoded, "://") {
			text = decoded
		}
	}
	var lines []string
	for _, l := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		l = strings.TrimSpace(l)
		if l != "" {
			lines = append(lines, l)
		}
	}
	return lines
}

func tryDecrypt(compact string, decrypt Decryptor, text *string) bool {
	plain, ok := decrypt(compact)
	if ok {
		*text = string(plain)
	}
	return ok
}

func tryBase64(s string) (string, bool) {
	if s == "" {
		return "", false
	}
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		if b, err := enc.DecodeString(s); err == nil && utf8.Valid(b) {
			return string(b), true
		}
	}
	return "", false
}

type UserInfo struct{ Upload, Download, Total, Expire int64 }

type Headers struct {
	Title               string
	UpdateIntervalHours int
	UserInfo            *UserInfo
	SupportURL          string
	WebPageURL          string
	Announce            string
}

func ParseHeaders(h http.Header) Headers {
	out := Headers{UpdateIntervalHours: 24}
	out.Title = maybeBase64(h.Get("Profile-Title"))
	out.Announce = maybeBase64(h.Get("Announce"))
	out.SupportURL = strings.TrimSpace(h.Get("Support-Url"))
	out.WebPageURL = strings.TrimSpace(h.Get("Profile-Web-Page-Url"))
	if n, err := strconv.Atoi(strings.TrimSpace(h.Get("Profile-Update-Interval"))); err == nil && n > 0 {
		out.UpdateIntervalHours = n
	}
	if ui := strings.TrimSpace(h.Get("Subscription-Userinfo")); ui != "" {
		info := &UserInfo{}
		for _, part := range strings.Split(ui, ";") {
			k, v, _ := strings.Cut(strings.TrimSpace(part), "=")
			n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
			if err != nil {
				continue
			}
			switch strings.ToLower(strings.TrimSpace(k)) {
			case "upload":
				info.Upload = n
			case "download":
				info.Download = n
			case "total":
				info.Total = n
			case "expire":
				info.Expire = n
			}
		}
		out.UserInfo = info
	}
	return out
}

func maybeBase64(v string) string {
	v = strings.TrimSpace(v)
	if rest, ok := strings.CutPrefix(v, "base64:"); ok {
		if d, ok := tryBase64(rest); ok {
			return strings.TrimSpace(d)
		}
	}
	return v
}

// Entries parses every line; ids are stable across refreshes by (list, label),
// a repeated label counted up so two rooms named alike stay two entries.
func Entries(subURL string, lines []string) []Entry {
	seen := map[string]int{}
	out := make([]Entry, 0, len(lines))
	for _, raw := range lines {
		e := Entry{Raw: raw}
		switch {
		case strings.HasPrefix(raw, olcrtcPrefix):
			l, err := ParseOlcrtc(raw)
			if err != nil {
				e.Kind, e.Problem, e.Label = KindUnsupported, err.Error(), raw
			} else {
				e.Kind, e.Olcrtc, e.Label = KindOlcrtc, l, l.Label
			}
		case strings.HasPrefix(raw, "vless://"):
			e.Kind, e.Label = KindVless, fragmentLabel(raw)
			if l, err := ParseVless(raw); err != nil {
				e.Problem = err.Error()
			} else {
				e.Vless, e.Label = l, l.Label
			}
		case strings.HasPrefix(raw, "hysteria2://"), strings.HasPrefix(raw, "hy2://"):
			e.Kind, e.Label = KindHysteria2, fragmentLabel(raw)
			if l, err := ParseHy2(raw); err != nil {
				e.Problem = err.Error()
			} else {
				e.Hy2, e.Label = l, l.Label
			}
		default:
			scheme, _, _ := strings.Cut(raw, "://")
			e.Kind, e.Label, e.Problem = KindUnsupported, raw, fmt.Sprintf("%s:// is not supported", scheme)
		}
		e.Country = CountryOf(e.Label)
		seen[e.Label]++
		e.ID = entryID(subURL, e.Label, seen[e.Label])
		out = append(out, e)
	}
	return out
}

func fragmentLabel(raw string) string {
	if i := strings.LastIndexByte(raw, '#'); i >= 0 {
		frag := raw[i+1:]
		if u, err := url.PathUnescape(frag); err == nil {
			frag = u
		}
		if l := strings.TrimSpace(frag); l != "" {
			return l
		}
	}
	return raw
}

func entryID(subURL, label string, ordinal int) string {
	h := sha256.Sum256([]byte(subURL + "\x00" + label + "\x00" + strconv.Itoa(ordinal)))
	return hex.EncodeToString(h[:6])
}

type Group struct {
	Country string
	Entries []Entry
}

// GroupByCountry keeps first-appearance order of countries and list order inside.
// Entries without a country are not grouped: they are reachable by label or index.
func GroupByCountry(entries []Entry) []Group {
	var out []Group
	index := map[string]int{}
	for _, e := range entries {
		if e.Country == "" {
			continue
		}
		i, ok := index[e.Country]
		if !ok {
			index[e.Country] = len(out)
			out = append(out, Group{Country: e.Country})
			i = len(out) - 1
		}
		out[i].Entries = append(out[i].Entries, e)
	}
	return out
}

var ErrNoMatch = errors.New("no such location")

// Select resolves a selector: a country code (its group), an exact label (one
// entry) or a 1-based index from `list`. Nothing is guessed.
func Select(entries []Entry, selector string) ([]Entry, error) {
	sel := strings.TrimSpace(selector)
	if sel == "" {
		return nil, fmt.Errorf("%w: empty selector", ErrNoMatch)
	}
	if n, err := strconv.Atoi(sel); err == nil {
		if n < 1 || n > len(entries) {
			return nil, fmt.Errorf("%w: index %d, the list has %d entries", ErrNoMatch, n, len(entries))
		}
		return []Entry{entries[n-1]}, nil
	}
	var byLabel []Entry
	for _, e := range entries {
		if e.Label == sel {
			byLabel = append(byLabel, e)
		}
	}
	if len(byLabel) == 1 {
		return byLabel, nil
	}
	if len(byLabel) > 1 {
		return nil, fmt.Errorf("%w: %d entries are named %q, pick one by index", ErrNoMatch, len(byLabel), sel)
	}
	groups := GroupByCountry(entries)
	for _, g := range groups {
		if strings.EqualFold(g.Country, sel) {
			return g.Entries, nil
		}
	}
	countries := make([]string, 0, len(groups))
	for _, g := range groups {
		countries = append(countries, g.Country)
	}
	return nil, fmt.Errorf("%w: %q; countries: %s; or a label or an index from `ghostlane list`", ErrNoMatch, sel, strings.Join(countries, " "))
}
