package daemon

import (
	"regexp"
	"strings"
)

var (
	hexKey  = regexp.MustCompile(`[0-9a-fA-F]{64}`)
	subPath = regexp.MustCompile(`/sub/[^/\s?#]+(?:/[^\s?#/]+)?`)
	tokenKV = regexp.MustCompile(`(?i)(token|key|password|pass)=([^&\s]+)`)
)

// Scrub hides keys, list tokens and credentials in a log line. List paths are
// masked the way store.MaskURL masks them: /sub/<token>[/unified|/olcrtc] and
// /sub/<partner>/<token> both lose the token.
func Scrub(s string) string {
	s = hexKey.ReplaceAllString(s, "<key>")
	s = subPath.ReplaceAllStringFunc(s, func(m string) string {
		parts := strings.Split(strings.TrimPrefix(m, "/sub/"), "/")
		if len(parts) == 2 && parts[1] != "unified" && parts[1] != "olcrtc" {
			return "/sub/" + parts[0] + "/…"
		}
		if len(parts) == 2 {
			return "/sub/…/" + parts[1]
		}
		return "/sub/…"
	})
	s = tokenKV.ReplaceAllString(s, "$1=…")
	return s
}
