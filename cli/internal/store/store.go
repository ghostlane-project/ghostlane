// Package store keeps the daemon's config and state under its state directory.
package store

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type Subscription struct {
	URL           string    `yaml:"url"`
	Title         string    `yaml:"title,omitempty"`
	IntervalHours int       `yaml:"interval_hours"`
	AddedAt       time.Time `yaml:"added_at"`
}

type Selection struct {
	Subscription string `yaml:"subscription"`
	Selector     string `yaml:"selector"`
	Mode         string `yaml:"mode"`                  // "tun" | "proxy"
	KillSwitch   bool   `yaml:"kill_switch,omitempty"` // tun only: refuse traffic outside the tunnel while no line is up
	EntryID      string `yaml:"entry_id,omitempty"`    // a numeric selector names a line, not a position: its id once resolved
}

type Proxy struct {
	Listen string `yaml:"listen"`
	Port   int    `yaml:"port"`
	User   string `yaml:"user,omitempty"`
	Pass   string `yaml:"pass,omitempty"`
}

type Config struct {
	Subscriptions []Subscription `yaml:"subscriptions"`
	Selection     *Selection     `yaml:"selection,omitempty"`
	Proxy         Proxy          `yaml:"proxy"`
}

func Defaults() *Config {
	return &Config{Proxy: Proxy{Listen: "127.0.0.1", Port: 1080}}
}

func Load(path string) (*Config, error) {
	c := Defaults()
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return c, nil
	}
	if err != nil {
		return nil, err
	}
	if err := yaml.Unmarshal(b, c); err != nil {
		return nil, err
	}
	if c.Proxy.Listen == "" {
		c.Proxy.Listen = "127.0.0.1"
	}
	if c.Proxy.Port == 0 {
		c.Proxy.Port = 1080
	}
	return c, nil
}

func Save(path string, c *Config) error {
	b, err := yaml.Marshal(c)
	if err != nil {
		return err
	}
	return writeAtomic(path, b, 0o600)
}

func writeAtomic(path string, data []byte, perm os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, perm); err != nil {
		return err
	}
	if err := os.Chmod(tmp, perm); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

type Cache struct {
	Body      []byte        `json:"body"`
	Headers   links.Headers `json:"headers"`
	FetchedAt time.Time     `json:"fetched_at"`
}

func cachePath(dir, subURL string) string {
	h := sha256.Sum256([]byte(subURL))
	return filepath.Join(dir, "lists", hex.EncodeToString(h[:8])+".json")
}

func LoadCache(dir, subURL string) (*Cache, error) {
	b, err := os.ReadFile(cachePath(dir, subURL))
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil //nolint:nilnil // a missing cache is not an error
	}
	if err != nil {
		return nil, err
	}
	var c Cache
	if err := json.Unmarshal(b, &c); err != nil {
		// a corrupt file would otherwise never be re-fetched
		_ = os.Remove(cachePath(dir, subURL))
		return nil, nil //nolint:nilnil // absent
	}
	return &c, nil
}

func SaveCache(dir, subURL string, c *Cache) error {
	b, err := json.Marshal(c)
	if err != nil {
		return err
	}
	return writeAtomic(cachePath(dir, subURL), b, 0o600)
}

func lastGoodPath(dir string) string { return filepath.Join(dir, "last-good.json") }

func LoadLastGood(dir string) (map[string]string, error) {
	m := map[string]string{}
	b, err := os.ReadFile(lastGoodPath(dir))
	if errors.Is(err, os.ErrNotExist) {
		return m, nil
	}
	if err != nil {
		return nil, err
	}
	return m, json.Unmarshal(b, &m)
}

func SaveLastGood(dir string, m map[string]string) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	return writeAtomic(lastGoodPath(dir), b, 0o600)
}

// MaskURL hides the credential part of a list URL: the token after /sub/ (or
// after /sub/<partner>/ when the partner id is the shorter segment), or the
// whole path when the URL is not of that shape.
func MaskURL(u string) string {
	p, err := url.Parse(u)
	if err != nil {
		return "…"
	}
	segs := strings.Split(strings.Trim(p.Path, "/"), "/")
	masked := false
	for i, s := range segs {
		if s != "sub" || i+1 >= len(segs) {
			continue
		}
		// /sub/<token>[/unified|/olcrtc] is ours; /sub/<partner>/<token> is a
		// partner's: the token is the last segment that is not a known suffix.
		if i+2 < len(segs) && segs[i+2] != "unified" && segs[i+2] != "olcrtc" {
			segs[i+2] = "…"
		} else {
			segs[i+1] = "…"
		}
		masked = true
		break
	}
	if !masked {
		segs = []string{"…"}
	}
	out := p.Scheme + "://" + p.Host + "/" + strings.Join(segs, "/")
	if p.RawQuery != "" {
		// every query value but the list-variant selector is masked: tokens travel there too
		var q []string
		for _, kv := range strings.Split(p.RawQuery, "&") {
			k, _, _ := strings.Cut(kv, "=")
			if k == "c" {
				q = append(q, kv)
			} else {
				q = append(q, k+"=…")
			}
		}
		out += "?" + strings.Join(q, "&")
	}
	return out
}

// Clone returns a deep copy, for saving outside the caller's lock.
func (c *Config) Clone() *Config {
	out := *c
	out.Subscriptions = append([]Subscription(nil), c.Subscriptions...)
	if c.Selection != nil {
		s := *c.Selection
		out.Selection = &s
	}
	return &out
}
