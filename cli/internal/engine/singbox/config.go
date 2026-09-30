// Package singbox is the front: a tun or a local proxy in front of the engine's SOCKS.
package singbox

import (
	"encoding/json"
	"errors"
	"net"
	"strconv"
)

type Mode string

const (
	ModeProxy Mode = "proxy"
	ModeTun   Mode = "tun"

	TableIndex = 2022
	RuleIndex  = 9000
	TunName    = "ghostlane0"
)

type FrontParams struct {
	Mode          Mode
	UpstreamAddr  string // the engine's SOCKS, host:port
	UpstreamUser  string
	UpstreamPass  string
	ProxyListen   string
	ProxyPort     int
	ProxyUser     string
	ProxyPass     string
	ExcludeUID    int
	InterfaceName string
	LogLevel      string
}

var privateRanges = []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
	"169.254.0.0/16", "127.0.0.0/8", "224.0.0.0/4", "fc00::/7", "fe80::/10", "ff00::/8"}

var (
	errProxyNeedsAuth = errors.New("a proxy that listens beyond loopback needs a user and a password")
	errBadMode        = errors.New("mode must be tun or proxy")
)

// BuildConfig emits the sing-box JSON of the spec's §6–§7.
func BuildConfig(p FrontParams) ([]byte, error) {
	host, portStr, err := net.SplitHostPort(p.UpstreamAddr)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portStr)
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
	upstream := map[string]any{"type": "socks", "tag": "tunnel", "server": host, "server_port": port, "version": "5"}
	if p.UpstreamUser != "" {
		upstream["username"], upstream["password"] = p.UpstreamUser, p.UpstreamPass
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
		"inbounds":  []map[string]any{inbound},
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
