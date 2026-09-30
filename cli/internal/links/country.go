package links

import (
	"strings"
	"unicode/utf8"
)

// CountryOf is the app's TransportGroup.countryOf with one addition: a leading
// flag (regional-indicator pair) is stripped first, because a partner's labels
// start with one ("🇩🇪 DE · VP8") and the app's rule alone reads the flag as
// the first token and finds no country.
func CountryOf(label string) string {
	s := strings.TrimSpace(label)
	for {
		r, size := utf8.DecodeRuneInString(s)
		if r >= 0x1F1E6 && r <= 0x1F1FF { // regional indicator symbols
			s = strings.TrimSpace(s[size:])
			continue
		}
		break
	}
	first := s
	if i := strings.IndexByte(first, ' '); i >= 0 {
		first = first[:i]
	}
	if i := strings.IndexByte(first, '|'); i >= 0 {
		first = first[:i]
	}
	first = strings.TrimSpace(first)
	if len(first) != 2 || first[0] < 'A' || first[0] > 'Z' || first[1] < 'A' || first[1] > 'Z' {
		return ""
	}
	return first
}
