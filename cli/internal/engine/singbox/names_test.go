//go:build with_utls

package singbox

import (
	"context"
	"testing"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
)

// sing-box refuses a uTLS fingerprint or a VLESS flow it does not name, and
// the refusal stops the whole front. Links carry Xray's names.
func TestXrayNamesBecomeSingBoxNames(t *testing.T) {
	for fp, want := range map[string]string{
		"chrome": "chrome", "Chrome": "chrome", " firefox ": "firefox", "randomized": "randomized",
		"hellochrome_133": "chrome", "chrome_auto": "chrome", "hellofirefox_148": "firefox", "hellosafari_26_3": "safari",
		"helloios_14": "ios", "helloedge_85": "edge", "hello360_7_5": "360", "helloqq_11_1": "qq",
		"helloandroid_11_okhttp": "android", "randomizednoalpn": "randomized", "unsafe": "chrome", "": "chrome",
	} {
		if got := singBoxFingerprint(fp); got != want {
			t.Errorf("fp %q: %q, want %q", fp, got, want)
		}
	}
	for flow, want := range map[string]string{
		"": "", "xtls-rprx-vision": "xtls-rprx-vision", "xtls-rprx-vision-udp443": "xtls-rprx-vision",
		" XTLS-RPRX-VISION ": "xtls-rprx-vision", "xtls-rprx-direct": "xtls-rprx-direct",
	} {
		if got := singBoxFlow(flow); got != want {
			t.Errorf("flow %q: %q, want %q", flow, got, want)
		}
	}
}

// The front starts on a line written with Xray's names: sing-box resolves
// both when it makes the outbound, so a name it refuses fails Start.
func TestFrontStartsOnALineWithXrayNames(t *testing.T) {
	for _, fp := range []string{"hellochrome_133", "randomizednoalpn", "unsafe", "Firefox"} {
		t.Run(fp, func(t *testing.T) {
			port, _ := olcrtc.FreePort()
			p := base(ModeProxy)
			p.ProxyPort, p.ProbeListen = port, ""
			line := vlessTCP()
			line.Fingerprint, line.Flow = fp, "xtls-rprx-vision-udp443"
			p.Upstream = Upstream{Vless: line}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			f, err := Start(ctx, p)
			if err != nil {
				t.Fatalf("the front refused a line with fp=%s flow=xtls-rprx-vision-udp443: %v", fp, err)
			}
			_ = f.Close()
		})
	}
}
