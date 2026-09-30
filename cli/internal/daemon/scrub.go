package daemon

import (
	"regexp"

	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

var (
	hexKey  = regexp.MustCompile(`[0-9a-fA-F]{64}`)
	anyURL  = regexp.MustCompile(`https?://[^\s"'<>]+`)
	subPath = regexp.MustCompile(`/sub/[^/\s?#"']+(?:/[^\s?#/"']+)?`)
	tokenKV = regexp.MustCompile(`(?i)(token|key|password|pass)=([^&\s"']+)`)
)

// Scrub hides keys, list URLs and credentials in a log line. A URL keeps its
// scheme and host and loses the rest the way store.MaskURL masks it; a bare
// /sub/ path (no scheme) is masked the same way.
func Scrub(s string) string {
	s = hexKey.ReplaceAllString(s, "<key>")
	s = anyURL.ReplaceAllStringFunc(s, store.MaskURL)
	s = subPath.ReplaceAllStringFunc(s, func(m string) string { return store.MaskURL("x://h" + m)[len("x://h"):] })
	s = tokenKV.ReplaceAllString(s, "$1=…")
	return s
}
