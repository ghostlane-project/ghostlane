package links

import (
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

var (
	ErrNotVless             = errors.New("not a vless:// line")
	ErrNotHy2               = errors.New("not a hysteria2:// line")
	ErrUnsupportedTransport = errors.New("transport not supported")
	errNoCredentials        = errors.New("no credentials before @")
	errNoHostPort           = errors.New("no host:port")
	errBadPort              = errors.New("bad port")
	errEmptyParts           = errors.New("empty host or credentials")
)

// Transport is the stream a VLESS line asks for: plain TCP (Reality), or XHTTP.
type Transport struct {
	Kind string // "tcp" | "xhttp"
	Path string
	Host string
	Mode string
}

// VlessLine is vless://<uuid>@host:port?…#label as the app's LinkParser reads it.
type VlessLine struct {
	UUID        string
	Host        string
	Port        int
	SNI         string
	PublicKey   string // Reality pbk; "" = plain TLS (XHTTP through a CDN)
	ShortID     string
	Fingerprint string
	Flow        string // tcp only
	Transport   Transport
	Label       string
}

// Hy2Line is hysteria2://<password>@host:port?…#label.
type Hy2Line struct {
	Password     string
	Host         string
	Port         int
	SNI          string
	ObfsPassword string
	PinSHA256    string
	Insecure     bool
	Label        string
}

type shareParts struct {
	userinfo string
	host     string
	port     int
	query    url.Values
	tag      string
}

// splitShare is the app's LinkParser.splitLink: scheme://userinfo@host:port?query#tag.
func splitShare(s, scheme string) (*shareParts, error) {
	body := strings.TrimPrefix(s, scheme)
	tag := ""
	if i := strings.IndexByte(body, '#'); i >= 0 {
		tag = body[i+1:]
		// QueryUnescape, as the app's urlDecode: a + is a space
		if t, err := url.QueryUnescape(tag); err == nil {
			tag = t
		}
		body = body[:i]
	}
	query := url.Values{}
	if i := strings.IndexByte(body, '?'); i >= 0 {
		q, err := url.ParseQuery(body[i+1:])
		if err != nil {
			return nil, fmt.Errorf("query: %w", err)
		}
		query = q
		body = body[:i]
	}
	at := strings.LastIndexByte(body, '@')
	if at < 0 {
		return nil, errNoCredentials
	}
	userinfo, hostPort := body[:at], body[at+1:]
	colon := strings.LastIndexByte(hostPort, ':')
	if colon <= 0 {
		return nil, errNoHostPort
	}
	port, err := strconv.Atoi(hostPort[colon+1:])
	if err != nil || port < 1 || port > 65535 {
		return nil, errBadPort
	}
	host := strings.Trim(hostPort[:colon], "[]")
	if host == "" || userinfo == "" {
		return nil, errEmptyParts
	}
	return &shareParts{userinfo: userinfo, host: host, port: port, query: query, tag: strings.TrimSpace(tag)}, nil
}

func ParseVless(line string) (*VlessLine, error) {
	line = strings.TrimSpace(line)
	if !strings.HasPrefix(line, "vless://") {
		return nil, ErrNotVless
	}
	p, err := splitShare(line, "vless://")
	if err != nil {
		return nil, fmt.Errorf("vless line: %w", err)
	}
	q := p.query
	typ := strings.ToLower(strings.TrimSpace(q.Get("type")))
	if typ == "" {
		typ = "tcp"
	}
	l := &VlessLine{UUID: p.userinfo, Host: p.host, Port: p.port, SNI: q.Get("sni"), PublicKey: q.Get("pbk"),
		ShortID: q.Get("sid"), Fingerprint: q.Get("fp"), Label: p.tag}
	if l.Fingerprint == "" {
		l.Fingerprint = "chrome"
	}
	if l.Label == "" {
		l.Label = p.host
	}
	switch typ {
	case "tcp", "raw":
		l.Transport = Transport{Kind: "tcp"}
		l.Flow = strings.TrimSpace(q.Get("flow"))
	case "xhttp":
		host := q.Get("host")
		if host == "" {
			host = l.SNI
		}
		path := q.Get("path")
		if path == "" {
			path = "/"
		}
		mode := q.Get("mode")
		if mode == "" {
			mode = "auto"
		}
		l.Transport = Transport{Kind: "xhttp", Path: path, Host: host, Mode: mode}
	default:
		return nil, fmt.Errorf("%w: vless over %s", ErrUnsupportedTransport, typ)
	}
	return l, nil
}

func ParseHy2(line string) (*Hy2Line, error) {
	line = strings.TrimSpace(line)
	scheme := ""
	for _, s := range []string{"hysteria2://", "hy2://"} {
		if strings.HasPrefix(line, s) {
			scheme = s
		}
	}
	if scheme == "" {
		return nil, ErrNotHy2
	}
	p, err := splitShare(line, scheme)
	if err != nil {
		return nil, fmt.Errorf("hysteria2 line: %w", err)
	}
	q := p.query
	ins := q.Get("insecure")
	l := &Hy2Line{Password: p.userinfo, Host: p.host, Port: p.port, SNI: q.Get("sni"), ObfsPassword: q.Get("obfs-password"),
		PinSHA256: q.Get("pinSHA256"), Insecure: ins == "1" || ins == "true", Label: p.tag}
	if l.Label == "" {
		l.Label = p.host
	}
	return l, nil
}
