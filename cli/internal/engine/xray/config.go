package xray

import (
	"encoding/json"
	"errors"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

// maxEachPostBytes is XrayConfig.XHTTP_MAX_EACH_POST_BYTES in the app: the
// upload chunk of packet-up, bounding what Xray buffers.
const maxEachPostBytes = 200_000

var errNotXhttp = errors.New("xray: only xhttp lines run here; Reality tcp and hysteria2 are sing-box's")

type Params struct {
	Line      links.VlessLine
	SocksHost string
	SocksPort int
	SocksUser string
	SocksPass string
	LogLevel  string
}

// BuildConfig is XrayConfig.buildXhttp without the routing and DNS a phone
// needs: a loopback SOCKS in, one vless+xhttp outbound out.
func BuildConfig(p Params) ([]byte, error) {
	l := p.Line
	if l.Transport.Kind != "xhttp" {
		return nil, errNotXhttp
	}
	level := p.LogLevel
	if level == "" {
		level = "warning"
	}
	stream := map[string]any{
		"network":       "xhttp",
		"xhttpSettings": map[string]any{"path": l.Transport.Path, "host": l.Transport.Host, "mode": l.Transport.Mode, "scMaxEachPostBytes": maxEachPostBytes},
	}
	switch {
	case l.Plain:
		stream["security"] = "none"
	case l.PublicKey == "":
		stream["security"] = "tls"
		stream["tlsSettings"] = map[string]any{"serverName": l.SNI, "fingerprint": l.Fingerprint, "allowInsecure": false}
	default:
		stream["security"] = "reality"
		stream["realitySettings"] = map[string]any{"serverName": l.SNI, "fingerprint": l.Fingerprint, "publicKey": l.PublicKey, "shortId": l.ShortID}
	}
	cfg := map[string]any{
		"log": map[string]any{"loglevel": level},
		"inbounds": []map[string]any{{
			"tag": "in", "listen": p.SocksHost, "port": p.SocksPort, "protocol": "socks",
			"settings": map[string]any{"auth": "password", "accounts": []map[string]any{{"user": p.SocksUser, "pass": p.SocksPass}}, "udp": true},
		}},
		"outbounds": []map[string]any{{
			"tag": "out", "protocol": "vless",
			"settings":       map[string]any{"vnext": []map[string]any{{"address": l.Host, "port": l.Port, "users": []map[string]any{{"id": l.UUID, "encryption": "none"}}}}},
			"streamSettings": stream,
		}},
	}
	return json.Marshal(cfg)
}
