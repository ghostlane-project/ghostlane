// Package singbox is the front: a tun or a local proxy in front of the engine's SOCKS.
package singbox

import (
	"encoding/json"
	"errors"
	"net"
	"strconv"
	"strings"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type Mode string

const (
	ModeProxy Mode = "proxy"
	ModeTun   Mode = "tun"

	TableIndex = 2022
	RuleIndex  = 9000
	TunName    = "ghostlane0"
)

// Upstream is where the front sends traffic: an engine's loopback SOCKS
// (olcRTC, Xray), or a native sing-box outbound (VLESS Reality over tcp,
// Hysteria2). Exactly one field is set.
type Upstream struct {
	Socks *SocksUpstream
	Vless *links.VlessLine
	Hy2   *links.Hy2Line
}

type SocksUpstream struct {
	Addr string // host:port
	User string
	Pass string
}

type FrontParams struct {
	Mode          Mode
	Upstream      Upstream
	ProxyListen   string
	ProxyPort     int
	ProxyUser     string
	ProxyPass     string
	ExcludeUID    int
	InterfaceName string
	LogLevel      string
	// A loopback mixed inbound the daemon probes through, for every kind of
	// upstream; "" = none. Credentials are required.
	ProbeListen string
	ProbeUser   string
	ProbePass   string
}

var privateRanges = []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
	"169.254.0.0/16", "127.0.0.0/8", "224.0.0.0/4", "fc00::/7", "fe80::/10", "ff00::/8"}

var (
	errProxyNeedsAuth  = errors.New("a proxy that listens beyond loopback needs a user and a password")
	errBadMode         = errors.New("mode must be tun or proxy")
	errNoUpstream      = errors.New("front: no upstream")
	errXhttpNotSingBox = errors.New("front: xhttp lines run in Xray, not sing-box")
	errProbeNeedsAuth  = errors.New("front: the probe inbound needs credentials")
)

// upstreamOutbound is the `tunnel` outbound: the shapes of the app's
// SingBoxConfig.addOutbound for vless and hysteria2, or a socks hop.
func upstreamOutbound(u Upstream) (map[string]any, error) {
	switch {
	case u.Socks != nil:
		host, portStr, err := net.SplitHostPort(u.Socks.Addr)
		if err != nil {
			return nil, err
		}
		port, err := strconv.Atoi(portStr)
		if err != nil {
			return nil, err
		}
		out := map[string]any{"type": "socks", "tag": "tunnel", "server": host, "server_port": port, "version": "5"}
		if u.Socks.User != "" {
			out["username"], out["password"] = u.Socks.User, u.Socks.Pass
		}
		return out, nil
	case u.Vless != nil:
		v := u.Vless
		if v.Transport.Kind != "tcp" {
			return nil, errXhttpNotSingBox
		}
		out := map[string]any{"type": "vless", "tag": "tunnel", "server": v.Host, "server_port": v.Port, "uuid": v.UUID, "packet_encoding": "xudp"}
		// security=none is VLESS in the clear: a tls block there is a handshake
		// the server answers with a reset.
		if !v.Plain {
			tls := map[string]any{"enabled": true, "server_name": v.SNI, "utls": map[string]any{"enabled": true, "fingerprint": singBoxFingerprint(v.Fingerprint)}}
			switch {
			case v.PublicKey != "":
				tls["reality"] = map[string]any{"enabled": true, "public_key": v.PublicKey, "short_id": v.ShortID}
			case v.Insecure:
				tls["insecure"] = true
			}
			out["tls"] = tls
		}
		if flow := singBoxFlow(v.Flow); flow != "" {
			out["flow"] = flow
		}
		return out, nil
	case u.Hy2 != nil:
		h := u.Hy2
		out := map[string]any{"type": "hysteria2", "tag": "tunnel", "server": h.Host, "server_port": h.Port, "password": h.Password,
			"tls": map[string]any{"enabled": true, "server_name": h.SNI, "insecure": h.Insecure || h.PinSHA256 != ""}}
		if h.ObfsPassword != "" {
			out["obfs"] = map[string]any{"type": "salamander", "password": h.ObfsPassword}
		}
		return out, nil
	}
	return nil, errNoUpstream
}

// BuildConfig emits the sing-box JSON of the spec's §6–§7.
func BuildConfig(p FrontParams) ([]byte, error) {
	upstream, err := upstreamOutbound(p.Upstream)
	if err != nil {
		return nil, err
	}
	level := p.LogLevel
	if level == "" {
		level = "warn"
	}
	var inbound map[string]any
	switch p.Mode {
	case ModeProxy:
		if p.ProxyListen != "127.0.0.1" && p.ProxyListen != "::1" && p.ProxyListen != "localhost" && (p.ProxyUser == "" || p.ProxyPass == "") {
			return nil, errProxyNeedsAuth
		}
		inbound = map[string]any{"type": "mixed", "tag": "proxy-in", "listen": p.ProxyListen, "listen_port": p.ProxyPort}
		if p.ProxyUser != "" {
			inbound["users"] = []map[string]any{{"username": p.ProxyUser, "password": p.ProxyPass}}
		}
	case ModeTun:
		name := p.InterfaceName
		if name == "" {
			name = TunName
		}
		inbound = map[string]any{
			"type": "tun", "tag": "tun-in",
			"interface_name":        name,
			"address":               []string{"172.19.0.1/30", "fdfe:dcba:9876::1/126"},
			"auto_route":            true,
			"strict_route":          false,
			"iproute2_table_index":  TableIndex,
			"iproute2_rule_index":   RuleIndex,
			"exclude_uid":           []int{p.ExcludeUID},
			"route_exclude_address": privateRanges,
			"stack":                 "system",
		}
	default:
		return nil, errBadMode
	}
	inbounds := []map[string]any{inbound}
	if p.ProbeListen != "" {
		if p.ProbeUser == "" || p.ProbePass == "" {
			return nil, errProbeNeedsAuth
		}
		phost, pportStr, err := net.SplitHostPort(p.ProbeListen)
		if err != nil {
			return nil, err
		}
		pport, err := strconv.Atoi(pportStr)
		if err != nil {
			return nil, err
		}
		inbounds = append(inbounds, map[string]any{"type": "mixed", "tag": "probe-in", "listen": phost, "listen_port": pport,
			"users": []map[string]any{{"username": p.ProbeUser, "password": p.ProbePass}}})
	}
	cfg := map[string]any{
		"log": map[string]any{"level": level, "timestamp": false},
		"dns": map[string]any{
			"servers": []map[string]any{
				{"type": "https", "tag": "remote", "server": "1.1.1.1", "detour": "tunnel"},
				{"type": "local", "tag": "local"},
			},
			"final": "remote",
		},
		"inbounds":  inbounds,
		"outbounds": []map[string]any{upstream, {"type": "direct", "tag": "direct"}},
		"route": map[string]any{
			"rules": []map[string]any{
				{"action": "sniff"},
				{"protocol": "dns", "action": "hijack-dns"},
				{"ip_is_private": true, "outbound": "direct"},
				{"ip_version": 6, "action": "reject"},
			},
			"final":                   "tunnel",
			"auto_detect_interface":   true,
			"default_domain_resolver": "local",
		},
	}
	return json.Marshal(cfg)
}

// singBoxFingerprints are the uTLS names sing-box knows (common/tls/utls_client.go).
var singBoxFingerprints = map[string]bool{
	"chrome": true, "firefox": true, "edge": true, "safari": true, "360": true, "qq": true, "ios": true,
	"android": true, "random": true, "randomized": true,
	"chrome_psk": true, "chrome_psk_shuffle": true, "chrome_padding_psk_shuffle": true, "chrome_pq": true, "chrome_pq_psk": true,
}

// singBoxFingerprint is a link's fp as sing-box names it, the same rule as the
// app's SingBoxConfig.singBoxFingerprint. sing-box refuses a name it does not
// know, and that stops the whole configuration; links carry Xray's names too
// (hellochrome_133, randomizednoalpn, unsafe, Chrome). Each becomes the
// nearest name sing-box has, and one with none becomes chrome, the default.
func singBoxFingerprint(fp string) string {
	name := strings.ToLower(strings.TrimSpace(fp))
	switch {
	case name == "":
		return "chrome"
	case singBoxFingerprints[name]:
		return name
	case strings.HasPrefix(name, "chrome"), strings.HasPrefix(name, "hellochrome"):
		return "chrome"
	case strings.HasPrefix(name, "hellofirefox"):
		return "firefox"
	case strings.HasPrefix(name, "hellosafari"):
		return "safari"
	case strings.HasPrefix(name, "helloios"):
		return "ios"
	case strings.HasPrefix(name, "helloedge"):
		return "edge"
	case strings.HasPrefix(name, "hello360"):
		return "360"
	case strings.HasPrefix(name, "helloqq"):
		return "qq"
	case strings.HasPrefix(name, "helloandroid"):
		return "android"
	case strings.HasPrefix(name, "randomized"):
		return "randomized"
	}
	return "chrome"
}

// singBoxFlow is a link's flow as sing-box takes it: it knows one flow,
// xtls-rprx-vision, and refuses any other name. Xray's xtls-rprx-vision-udp443
// is that same Vision (in Xray it also lets UDP to port 443 through), so it is
// written as Vision; any other name stays, for sing-box to refuse by name.
func singBoxFlow(flow string) string {
	written := strings.TrimSpace(flow)
	switch strings.ToLower(written) {
	case "xtls-rprx-vision", "xtls-rprx-vision-udp443":
		return "xtls-rprx-vision"
	}
	return written
}
