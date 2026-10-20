package links

import (
	"context"
	"encoding/base64"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"unicode/utf8"
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
	got, headers, err := Fetch(context.Background(), srv.Client(), srv.URL+"/sub/a/b?c=olcbox", UserAgentPrefix+"0.0.1 (linux)", Device{})
	if err != nil {
		t.Fatal(err)
	}
	if lines := DecodeBody(got); len(lines) != 1 || headers.UpdateIntervalHours != 2 || ua != "Ghostlane-cli/0.0.1 (linux)" {
		t.Fatalf("lines=%v headers=%+v ua=%q", lines, headers, ua)
	}
	if _, _, err = Fetch(context.Background(), srv.Client(), srv.URL+"/sub/a/b", "x", Device{}); err == nil {
		t.Fatal("a 404 is an error")
	}
}

func TestFetchErrorHidesURL(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	u := srv.URL + "/sub/partner/TOKEN456?c=olcbox"
	srv.Close()
	_, _, err := Fetch(context.Background(), &http.Client{}, u, "x", Device{})
	if err == nil || strings.Contains(err.Error(), "TOKEN456") || strings.Contains(err.Error(), "/sub/") {
		t.Fatalf("the error must not carry the URL: %v", err)
	}
}

// listHost answers every fetch with fn and keeps the headers of the request it
// saw last.
type listHost struct {
	*httptest.Server
	mu   sync.Mutex
	seen http.Header
}

func newListHost(t *testing.T, fn http.HandlerFunc) *listHost {
	t.Helper()
	h := &listHost{}
	h.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.mu.Lock()
		h.seen = r.Header.Clone()
		h.mu.Unlock()
		fn(w, r)
	}))
	t.Cleanup(h.Close)
	return h
}

func (h *listHost) request() http.Header {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.seen
}

func servedList(w http.ResponseWriter, _ *http.Request) {
	_, _ = w.Write([]byte("olcrtc://telemost?vp8channel@r#" + key + "$DE · olcRTC\n"))
}

// A list host with a device limit counts devices by x-hwid, and the other
// three headers are what its owner sees beside the count.
func TestFetchNamesTheDevice(t *testing.T) {
	host := newListHost(t, servedList)
	dev := Device{ID: "4f0c1d2e-9a7b-4c3d-8e1f-0a1b2c3d4e5f", OS: "Linux", OSVersion: "6.12.0-116.el10.x86_64", Model: "PC"}
	if _, _, err := Fetch(context.Background(), host.Client(), host.URL+"/sub/a/b", UserAgentPrefix+"0.0.1 (linux)", dev); err != nil {
		t.Fatal(err)
	}
	seen := host.request()
	for name, want := range map[string]string{
		"x-hwid": dev.ID, "x-device-os": "Linux", "x-ver-os": "6.12.0-116.el10.x86_64", "x-device-model": "PC",
		"User-Agent": "Ghostlane-cli/0.0.1 (linux)", "Accept": "text/plain, */*",
	} {
		if got := seen.Get(name); got != want {
			t.Errorf("%s = %q, want %q", name, got, want)
		}
	}
}

// With no id a strict host answers "not supported" and says so; an id made up
// for the occasion would be counted as a device of its own at every start.
func TestFetchSendsNoHwidWithoutAnID(t *testing.T) {
	host := newListHost(t, servedList)
	dev := Device{OS: "Linux", OSVersion: "6.12.0", Model: "PC"}
	if _, _, err := Fetch(context.Background(), host.Client(), host.URL+"/sub/a/b", "x", dev); err != nil {
		t.Fatal(err)
	}
	seen := host.request()
	if _, sent := seen["X-Hwid"]; sent {
		t.Fatalf("x-hwid = %q: no id, no header", seen.Get("x-hwid"))
	}
	if seen.Get("x-device-os") != "Linux" || seen.Get("x-device-model") != "PC" || seen.Get("x-ver-os") != "6.12.0" {
		t.Fatalf("the device is still named: %v", seen)
	}
	// nothing at all is no header at all, not an empty one
	if _, _, err := Fetch(context.Background(), host.Client(), host.URL+"/sub/a/b", "x", Device{}); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"X-Hwid", "X-Device-Os", "X-Ver-Os", "X-Device-Model"} {
		if _, sent := host.request()[name]; sent {
			t.Errorf("%s was sent for an empty device", name)
		}
	}
}

// What the server reads of a device field is 64 printable ASCII characters; the
// client sends no more, and no control character reaches a header.
func TestFetchBoundsTheDeviceFields(t *testing.T) {
	host := newListHost(t, servedList)
	dev := Device{
		ID:        strings.Repeat("a", 100),
		OS:        "Linux\r\nX-Injected: 1",
		OSVersion: "6.12.0-" + strings.Repeat("é", 10) + strings.Repeat("9", 100),
		Model:     "Тел",
	}
	if _, _, err := Fetch(context.Background(), host.Client(), host.URL+"/sub/a/b", "x", dev); err != nil {
		t.Fatal(err)
	}
	seen := host.request()
	if got := seen.Get("x-hwid"); got != strings.Repeat("a", 64) {
		t.Errorf("x-hwid = %q", got)
	}
	if got := seen.Get("x-device-os"); got != "LinuxX-Injected: 1" {
		t.Errorf("x-device-os = %q", got)
	}
	if got := seen.Get("x-ver-os"); got != "6.12.0-"+strings.Repeat("9", 57) {
		t.Errorf("x-ver-os = %q", got)
	}
	if _, sent := seen["X-Device-Model"]; sent {
		t.Errorf("x-device-model = %q: nothing printable was left", seen.Get("x-device-model"))
	}
	if _, sent := seen["X-Injected"]; sent {
		t.Error("a line break in a field made a header of its own")
	}
}

// The same cases as the app's asciiHeader (DeviceDescriptionTest): the CLI and
// the app say a device in the same words.
func TestAsciiHeader(t *testing.T) {
	for _, tc := range []struct{ in, want string }{
		{strings.Repeat("a", 70), strings.Repeat("a", 64)},
		{strings.Repeat("a", 64), strings.Repeat("a", 64)},
		{"Café", "Caf"},
		{"Pixel\t 7é Pro", "Pixel 7 Pro"},
		{"a\nb\r\x00c\x7fd\x1be", "abcde"},
		{strings.Repeat("x", 60) + "é" + strings.Repeat("y", 10), strings.Repeat("x", 60) + "yyyy"},
		{"", ""},
		{"   ", ""},
		{"Тел", ""},
		{"  iPhone 13  ", "iPhone 13"},
		{"\xff\xfe", ""}, // not UTF-8 at all: dropped, not a crash
	} {
		if got := asciiHeader(tc.in); got != tc.want {
			t.Errorf("asciiHeader(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

const refusedSentence = "Acme: this link is already in use on its 2 devices. Ask Acme to free one."

// refusingHost answers as the platform does (spec 7.4): HTTP 200, the flags the
// apps read, the sentence in announce, the partner's usual headers, and for a
// body one server that goes nowhere and is named by the sentence.
func refusingHost(t *testing.T, flag string) *listHost {
	t.Helper()
	return newListHost(t, func(w http.ResponseWriter, _ *http.Request) {
		h := w.Header()
		h.Set(flag, "true")
		h.Set("x-hwid-active", "true")
		h.Set("x-hwid-limit", "true")
		h.Set("announce", "base64:"+base64.StdEncoding.EncodeToString([]byte(refusedSentence)))
		h.Set("profile-title", "base64:"+base64.StdEncoding.EncodeToString([]byte("Acme")))
		h.Set("profile-update-interval", "1")
		h.Set("subscription-userinfo", "upload=0; download=5; total=100; expire=1854106383")
		_, _ = w.Write([]byte("vless://00000000-0000-4000-8000-000000000000@127.0.0.1:1?type=tcp&security=none#" + url.PathEscape(refusedSentence) + "\n"))
	})
}

func TestARefusalReturnsTheAnnounce(t *testing.T) {
	for _, tc := range []struct {
		flag string
		want Refusal
	}{
		{"x-hwid-max-devices-reached", RefusalLimit},
		{"x-hwid-not-supported", RefusalNoDevice},
	} {
		t.Run(tc.flag, func(t *testing.T) {
			host := refusingHost(t, tc.flag)
			body, headers, err := Fetch(context.Background(), host.Client(), host.URL+"/sub/a/b", "x", Device{ID: "4f0c1d2e-9a7b-4c3d-8e1f-0a1b2c3d4e5f"})
			var refused *ErrDeviceRefused
			if !errors.As(err, &refused) {
				t.Fatalf("a refusal is an ErrDeviceRefused, got %v", err)
			}
			if refused.Kind != tc.want || refused.Announce != refusedSentence {
				t.Fatalf("%+v", refused)
			}
			if len(body) != 0 {
				t.Fatalf("the placeholder line came back as a list: %q", body)
			}
			// the answer still says who the list is, how often to ask, how much is used
			if headers.Refusal != tc.want || headers.Announce != refusedSentence || headers.Title != "Acme" ||
				headers.UpdateIntervalHours != 1 || headers.UserInfo == nil || headers.UserInfo.Download != 5 {
				t.Fatalf("%+v", headers)
			}
			if msg := err.Error(); strings.Contains(msg, "/sub/") || !strings.Contains(msg, refusedSentence) {
				t.Fatalf("the error says what the host said, and nothing of the URL: %s", msg)
			}
		})
	}
}

// What comes back with a refusal is stored with the list, so the sentence in
// it is already one bounded line.
func TestARefusalKeepsOnlyABoundedLine(t *testing.T) {
	host := newListHost(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("x-hwid-max-devices-reached", "true")
		w.Header().Set("announce", "base64:"+base64.StdEncoding.EncodeToString([]byte("first\x1b[2J\nsecond "+strings.Repeat("я", 400))))
	})
	_, headers, err := Fetch(context.Background(), host.Client(), host.URL+"/sub/a/b", "x", Device{})
	var refused *ErrDeviceRefused
	if !errors.As(err, &refused) {
		t.Fatalf("%v", err)
	}
	if refused.Announce != headers.Announce || utf8.RuneCountInString(refused.Announce) != 200 || !strings.HasPrefix(refused.Announce, "first [2J second я") {
		t.Fatalf("%q", refused.Announce)
	}
}

// The flags are the host's word, only when they say true; a list whose answer
// carries the other headers of a limit (a served, counted device) is a list.
func TestAnAnswerWithoutARefusalFlagIsAList(t *testing.T) {
	host := newListHost(t, func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("x-hwid-active", "true")
		h.Set("x-hwid-limit", "true")
		h.Set("subscription-always-hwid-enable", "1")
		h.Set("x-hwid-max-devices-reached", "false")
		h.Set("x-hwid-not-supported", "0")
		servedList(w, r)
	})
	body, headers, err := Fetch(context.Background(), host.Client(), host.URL+"/sub/a/b", "x", Device{ID: "abcdefghij"})
	if err != nil || len(DecodeBody(body)) != 1 || headers.Refusal != RefusalNone {
		t.Fatalf("%v %q %+v", err, body, headers)
	}
}

// A host that sends no sentence is still understood; one that sends a long or
// odd one is shown cut to a line, never trusted with the terminal.
func TestARefusalIsTold(t *testing.T) {
	if got := RefusalText(RefusalLimit, ""); got != "This list is at its device limit. Ask your provider." {
		t.Errorf("limit: %q", got)
	}
	if got := RefusalText(RefusalNoDevice, "  "); !strings.Contains(got, "could not read this device's id") {
		t.Errorf("no device: %q", got)
	}
	if got := RefusalText(RefusalLimit, refusedSentence); got != refusedSentence {
		t.Errorf("the host's own sentence goes first: %q", got)
	}
	long := RefusalText(RefusalLimit, strings.Repeat("п", 500))
	if n := utf8.RuneCountInString(long); n != 200 || !utf8.ValidString(long) {
		t.Errorf("cut to 200 characters, got %d", n)
	}
	odd := RefusalText(RefusalLimit, "line one\n\x1b[2Jline\ttwo\u2028three")
	if odd != "line one [2Jline two three" {
		t.Errorf("control characters become spaces: %q", odd)
	}
	if got := RefusalText(RefusalLimit, "\x00\x1b"); got != "This list is at its device limit. Ask your provider." {
		t.Errorf("a sentence of nothing printable is none: %q", got)
	}
}
