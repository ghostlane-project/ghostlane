package links

import (
	"errors"
	"testing"
)

const uuid = "00000000-0000-4000-8000-000000000000"

func TestParseVlessRealityTCP(t *testing.T) {
	l, err := ParseVless("vless://" + uuid + "@203.0.113.9:443?type=tcp&security=reality&sni=yandex.ru&fp=chrome&pbk=PBK&sid=ab12&flow=xtls-rprx-vision#DE via RU | 0.13TON/GB")
	if err != nil {
		t.Fatal(err)
	}
	if l.UUID != uuid || l.Host != "203.0.113.9" || l.Port != 443 || l.SNI != "yandex.ru" || l.PublicKey != "PBK" || l.ShortID != "ab12" ||
		l.Fingerprint != "chrome" || l.Flow != "xtls-rprx-vision" || l.Transport.Kind != "tcp" || l.Label != "DE via RU | 0.13TON/GB" {
		t.Fatalf("%+v", l)
	}
	l, _ = ParseVless("vless://" + uuid + "@h.example:8443?security=reality&sni=s&pbk=P&sid=1")
	if l.Transport.Kind != "tcp" || l.Fingerprint != "chrome" || l.Label != "h.example" {
		t.Fatalf("%+v", l)
	}
}

func TestParseVlessXhttp(t *testing.T) {
	// Review Focus 5: flow is dropped for xhttp
	l, err := ParseVless("vless://" + uuid + "@45.153.230.44:8444?type=xhttp&security=reality&encryption=none&pbk=P&sid=S&fp=chrome&sni=yandex.ru&path=/pk&host=yandex.ru&mode=packet-up&flow=xtls-rprx-vision#%F0%9F%87%B7%F0%9F%87%BA%20EKB%20%C2%B7%20XHTTP")
	if err != nil {
		t.Fatal(err)
	}
	if l.Transport != (Transport{Kind: "xhttp", Path: "/pk", Host: "yandex.ru", Mode: "packet-up"}) || l.Flow != "" || l.Label != "🇷🇺 EKB · XHTTP" {
		t.Fatalf("%+v", l)
	}
	// Review Focus 2: CDN entry, TLS not Reality, host from the link
	l, _ = ParseVless("vless://" + uuid + "@cdn.example:443?type=xhttp&security=tls&path=/xh-de/&mode=stream-one&sni=cdn.example&host=cdn.example&fp=chrome&alpn=h2#CDN")
	if l.PublicKey != "" || l.Transport.Mode != "stream-one" || l.Transport.Host != "cdn.example" {
		t.Fatalf("%+v", l)
	}
	l, _ = ParseVless("vless://" + uuid + "@h:1?type=xhttp&security=reality&sni=s&pbk=P&sid=1")
	if l.Transport != (Transport{Kind: "xhttp", Path: "/", Host: "s", Mode: "auto"}) {
		t.Fatalf("%+v", l.Transport)
	}
}

func TestParseVlessRefusals(t *testing.T) {
	for _, bad := range []string{
		"vless://" + uuid + "@h:443?type=grpc&serviceName=x&security=reality&pbk=P",
		"vless://" + uuid + "@h:443?type=ws&path=/",
		"vless://" + uuid + "@h:443?type=httpupgrade",
	} {
		if _, err := ParseVless(bad); !errors.Is(err, ErrUnsupportedTransport) {
			t.Fatalf("%s: %v", bad, err)
		}
	}
	for _, bad := range []string{"vless://h:443", "vless://" + uuid + "@h", "vless://" + uuid + "@:443", "vless://" + uuid + "@h:x"} {
		if _, err := ParseVless(bad); err == nil || errors.Is(err, ErrUnsupportedTransport) {
			t.Fatalf("%s: want a parse error, got %v", bad, err)
		}
	}
	if _, err := ParseVless("hy2://x@h:1"); !errors.Is(err, ErrNotVless) {
		t.Fatal(err)
	}
}

func TestParseHy2(t *testing.T) {
	l, err := ParseHy2("hysteria2://3b8cdf3e-061c-4230-8e47-28a838ec91e0@45.153.230.44:38443?sni=s.example&obfs=salamander&obfs-password=470b5651&insecure=0&pinSHA256=abcd#%F0%9F%87%B7%F0%9F%87%BA%20EKB%20%C2%B7%20Hy2")
	if err != nil {
		t.Fatal(err)
	}
	if l.Password != "3b8cdf3e-061c-4230-8e47-28a838ec91e0" || l.Port != 38443 || l.SNI != "s.example" || l.ObfsPassword != "470b5651" || l.PinSHA256 != "abcd" || l.Insecure || l.Label != "🇷🇺 EKB · Hy2" {
		t.Fatalf("%+v", l)
	}
	// Review Focus 3 shapes
	l, _ = ParseHy2("hy2://p@h:443?sni=s&insecure=1")
	if !l.Insecure || l.ObfsPassword != "" || l.PinSHA256 != "" {
		t.Fatalf("%+v", l)
	}
	if _, err := ParseHy2("hysteria2://h:443"); err == nil {
		t.Fatal("no password")
	}
	if _, err := ParseHy2("vless://x@h:1"); !errors.Is(err, ErrNotHy2) {
		t.Fatal(err)
	}
}

// A label with a literal + decodes as a space, as the app's urlDecode does.
func TestLabelPlusIsSpace(t *testing.T) {
	l, err := ParseVless("vless://" + uuid + "@h:443?type=tcp&security=reality&sni=s&pbk=P&sid=1#DE%20via+RU")
	if err != nil || l.Label != "DE via RU" {
		t.Fatalf("%+v %v", l, err)
	}
}
