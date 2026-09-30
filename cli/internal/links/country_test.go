package links

import "testing"

func TestCountryOf(t *testing.T) {
	for label, want := range map[string]string{
		"🇩🇪 DE · VP8":            "DE",
		"🇩🇪 DE · VP8 · WB":       "DE",
		"DE · olcRTC":            "DE",
		"US via RU | 0.13TON/GB": "US",
		"☁️ CDN → 🇪🇺":            "",
		"VPN MOBL":               "",
		"de · lower":             "",
		"  🇯🇵  JP":               "JP",
		"":                       "",
	} {
		if got := CountryOf(label); got != want {
			t.Fatalf("CountryOf(%q) = %q, want %q", label, got, want)
		}
	}
}
