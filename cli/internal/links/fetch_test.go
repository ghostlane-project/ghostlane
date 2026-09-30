package links

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestFetch(t *testing.T) {
	body := "olcrtc://telemost?vp8channel@r#" + key + "$DE · olcRTC\n"
	var ua string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ua = r.Header.Get("User-Agent")
		if r.URL.RawQuery != "c=olcbox" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Profile-Update-Interval", "2")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = w.Write([]byte(base64.StdEncoding.EncodeToString([]byte(body))))
	}))
	defer srv.Close()
	got, headers, err := Fetch(context.Background(), srv.Client(), srv.URL+"/sub/a/b?c=olcbox", UserAgentPrefix+"0.0.1 (linux)")
	if err != nil {
		t.Fatal(err)
	}
	if lines := DecodeBody(got); len(lines) != 1 || headers.UpdateIntervalHours != 2 || ua != "Ghostlane-cli/0.0.1 (linux)" {
		t.Fatalf("lines=%v headers=%+v ua=%q", lines, headers, ua)
	}
	if _, _, err = Fetch(context.Background(), srv.Client(), srv.URL+"/sub/a/b", "x"); err == nil {
		t.Fatal("a 404 is an error")
	}
}
