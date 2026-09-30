// Package pins reads the core versions the app pins and the ones cli/go.mod uses,
// so the two cannot drift apart unnoticed.
package pins

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

type Pins struct {
	Engine  string // pseudo-version of the olcrtc engine
	SingBox string // e.g. "1.13.14" from the repo, "v1.13.14" from go.mod
}

var (
	enginePinRe   = regexp.MustCompile(`(?m)^OLCRTC_VERSION="\$\{OLCRTC_VERSION:-([^}]+)\}"`)
	singboxRepoRe = regexp.MustCompile(`SINGBOX_VERSION:\s*"([^"]+)"`)
	replaceRe     = regexp.MustCompile(`(?m)^replace github\.com/openlibrecommunity/olcrtc => github\.com/ghostlane-project/olcrtc (\S+)`)
	singboxModRe  = regexp.MustCompile(`(?m)^\s*github\.com/sagernet/sing-box (\S+)`)
)

var errPinsNotFound = errors.New("pins: version markers not found")

func FromRepo(root string) (Pins, error) {
	pinsSh, err := os.ReadFile(filepath.Join(root, "scripts", "cores-pins.sh"))
	if err != nil {
		return Pins{}, err
	}
	release, err := os.ReadFile(filepath.Join(root, ".github", "workflows", "release.yml"))
	if err != nil {
		return Pins{}, err
	}
	m := enginePinRe.FindSubmatch(pinsSh)
	s := singboxRepoRe.FindSubmatch(release)
	if m == nil || s == nil {
		return Pins{}, errPinsNotFound
	}
	return Pins{Engine: string(m[1]), SingBox: string(s[1])}, nil
}

func FromGoMod(path string) (Pins, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return Pins{}, err
	}
	r := replaceRe.FindSubmatch(b)
	s := singboxModRe.FindSubmatch(b)
	if r == nil || s == nil {
		return Pins{}, errPinsNotFound
	}
	return Pins{Engine: strings.TrimSpace(string(r[1])), SingBox: strings.TrimSpace(string(s[1]))}, nil
}
