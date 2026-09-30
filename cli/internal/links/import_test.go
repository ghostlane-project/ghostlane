package links

import "testing"

func TestImportPayload(t *testing.T) {
	list := "https://sub.example/sub/a/b?c=olcbox"
	for in, want := range map[string]string{
		"ghostlane://add?url=https%3A%2F%2Fsub.example%2Fsub%2Fa%2Fb%3Fc%3Dolcbox": list,
		"ghostlane://add/" + list:               list,
		"ghostlane://import/" + list:            list,
		list:                                    list,
		"olcrtc://telemost?vp8channel@r#" + key: "olcrtc://telemost?vp8channel@r#" + key,
	} {
		if got := ImportPayload(in); got != want {
			t.Fatalf("ImportPayload(%q) = %q", in, got)
		}
	}
}
