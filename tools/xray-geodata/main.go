// xray-geodata trims v2fly's geosite (dlc.dat) and geoip (geoip.dat) to the
// lists Bypass Russia needs and writes them as text, one rule per line in the
// syntax Xray's routing and dns accept inline: `domain:`, `full:`, `keyword:`,
// `regexp:` for names, `a.b.c.d/n` for addresses.
//
// Text rather than trimmed .dat files on purpose. Xray finds a .dat through
// the `xray.location.asset` environment variable or next to its executable,
// and the tunnel extension on iOS can set neither: libXray 1.260711 has no
// way to hand an environment to the Go runtime (apiVersion 2 is refused),
// and the extension bundle is built by Xcode. Inline rules need no file at
// all, and cost the same memory once parsed.
//
// Usage: go run . -geosite dlc.dat -geoip geoip.dat -out <dir>
package main

import (
	"bufio"
	"flag"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"

	"github.com/xtls/xray-core/common/geodata"
	"google.golang.org/protobuf/proto"
)

var geositeCodes = []string{"category-ru", "tld-ru"}

const geoipCode = "ru"

// IPv6 prefixes are left out unless asked for: the iOS tunnel claims no IPv6
// route, so no IPv6 destination ever reaches these rules, and the v2fly list
// carries ten thousand of them - more bytes than everything else together.
var withIPv6 = flag.Bool("ipv6", false, "include IPv6 prefixes in the geoip list")

func main() {
	geosite := flag.String("geosite", "", "path to dlc.dat (v2fly domain-list-community)")
	geoip := flag.String("geoip", "", "path to geoip.dat (v2fly geoip)")
	out := flag.String("out", ".", "directory for the text lists")
	flag.Parse()
	if *geosite == "" || *geoip == "" {
		fmt.Fprintln(os.Stderr, "both -geosite and -geoip are required")
		os.Exit(2)
	}
	if err := os.MkdirAll(*out, 0o755); err != nil {
		fail(err)
	}
	if err := writeGeosite(*geosite, *out); err != nil {
		fail(err)
	}
	if err := writeGeoip(*geoip, *out); err != nil {
		fail(err)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "xray-geodata:", err)
	os.Exit(1)
}

func writeGeosite(path, out string) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var list geodata.GeoSiteList
	if err := proto.Unmarshal(raw, &list); err != nil {
		return fmt.Errorf("decode %s: %w", path, err)
	}
	byCode := map[string]*geodata.GeoSite{}
	for _, entry := range list.Entry {
		byCode[strings.ToLower(entry.Code)] = entry
	}
	for _, code := range geositeCodes {
		entry, ok := byCode[code]
		if !ok {
			return fmt.Errorf("%s has no code %q", path, code)
		}
		lines := make([]string, 0, len(entry.Domain))
		seen := map[string]bool{}
		for _, d := range entry.Domain {
			var prefix string
			switch d.Type {
			case geodata.Domain_Substr:
				prefix = "keyword:"
			case geodata.Domain_Regex:
				prefix = "regexp:"
			case geodata.Domain_Domain:
				prefix = "domain:"
			case geodata.Domain_Full:
				prefix = "full:"
			default:
				return fmt.Errorf("%s/%s: unknown domain type %v", path, code, d.Type)
			}
			// Attributes (@cn and the like) are dropped: the whole list is
			// what the app treats as Russian, attribute or not.
			line := prefix + d.Value
			if seen[line] {
				continue
			}
			seen[line] = true
			lines = append(lines, line)
		}
		if err := writeLines(filepath.Join(out, "geosite-"+code+".txt"), lines); err != nil {
			return err
		}
	}
	return nil
}

func writeGeoip(path, out string) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var list geodata.GeoIPList
	if err := proto.Unmarshal(raw, &list); err != nil {
		return fmt.Errorf("decode %s: %w", path, err)
	}
	for _, entry := range list.Entry {
		if strings.ToLower(entry.Code) != geoipCode {
			continue
		}
		if entry.ReverseMatch {
			return fmt.Errorf("%s/%s is a reverse-match list, which inline rules cannot express", path, geoipCode)
		}
		lines := make([]string, 0, len(entry.Cidr))
		for _, c := range entry.Cidr {
			ip := net.IP(c.Ip)
			switch len(c.Ip) {
			case net.IPv4len:
			case net.IPv6len:
				if !*withIPv6 {
					continue
				}
			default:
				return fmt.Errorf("%s/%s: address of %d bytes", path, geoipCode, len(c.Ip))
			}
			lines = append(lines, fmt.Sprintf("%s/%d", ip.String(), c.Prefix))
		}
		return writeLines(filepath.Join(out, "geoip-"+geoipCode+".txt"), lines)
	}
	return fmt.Errorf("%s has no code %q", path, geoipCode)
}

func writeLines(path string, lines []string) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	w := bufio.NewWriter(f)
	for _, line := range lines {
		if _, err := w.WriteString(line + "\n"); err != nil {
			return err
		}
	}
	if err := w.Flush(); err != nil {
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	fmt.Printf("%s: %d rules, %d bytes\n", filepath.Base(path), len(lines), info.Size())
	return nil
}
