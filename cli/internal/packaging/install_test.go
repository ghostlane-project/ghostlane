package packaging

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func tarball(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for name, body := range files {
		mode := int64(0o644)
		if name == "ghostlane" {
			mode = 0o755
		}
		if err := tw.WriteHeader(&tar.Header{Name: "./" + name, Mode: mode, Size: int64(len(body))}); err != nil {
			t.Fatal(err)
		}
		_, _ = tw.Write([]byte(body))
	}
	_ = tw.Close()
	_ = gz.Close()
	return buf.Bytes()
}

func TestInstallScriptTarball(t *testing.T) {
	if _, err := exec.LookPath("openssl"); err != nil {
		t.Skip("openssl missing")
	}
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(pub)
	pubPEM := pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der})
	pubFile := filepath.Join(t.TempDir(), "pub.pem")
	_ = os.WriteFile(pubFile, pubPEM, 0o644)

	tgz := tarball(t, map[string]string{"ghostlane": "#!/bin/sh\necho ghostlane 0.0.1\n", "ghostlane.service": "[Unit]\nExecStart=/usr/bin/ghostlane run\n", "README.md": "x", "LICENSE": "y", "COPYRIGHT": "z"})
	sum := sha256.Sum256(tgz)
	sums := hex.EncodeToString(sum[:]) + "  ghostlane-cli-0.0.1-linux-amd64.tar.gz\n"
	sig := ed25519.Sign(priv, []byte(sums))
	assets := map[string][]byte{
		"/ghostlane-cli-0.0.1-linux-amd64.tar.gz": tgz,
		"/ghostlane-cli-0.0.1-SHA256SUMS":         []byte(sums),
		"/ghostlane-cli-0.0.1-SHA256SUMS.sig":     sig,
	}
	var mu sync.Mutex
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		b, ok := assets[r.URL.Path]
		mu.Unlock()
		if !ok {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write(b)
	}))
	defer srv.Close()

	root := t.TempDir()
	runInstall := func() (string, error) {
		cmd := exec.Command("sh", "../../packaging/install.sh", "--version", "0.0.1", "--base-url", srv.URL, "--family", "tar", "--no-service")
		cmd.Env = append(os.Environ(), "GHOSTLANE_INSTALL_ROOT="+root, "GHOSTLANE_PUBKEY_FILE="+pubFile, "GHOSTLANE_ARCH=amd64")
		out, err := cmd.CombinedOutput()
		return string(out), err
	}
	out, err := runInstall()
	if err != nil {
		t.Fatalf("%v\n%s", err, out)
	}
	if b, err := os.ReadFile(filepath.Join(root, "usr/local/bin/ghostlane")); err != nil || !strings.Contains(string(b), "echo ghostlane") {
		t.Fatalf("binary not installed: %v\n%s", err, out)
	}
	if b, err := os.ReadFile(filepath.Join(root, "etc/systemd/system/ghostlane.service")); err != nil || !strings.Contains(string(b), "/usr/local/bin/ghostlane run") {
		t.Fatalf("unit not installed with the tarball path: %v\n%s", err, out)
	}
	set := func(k string, v []byte) { mu.Lock(); assets[k] = v; mu.Unlock() }
	// A tampered sums file must be refused.
	set("/ghostlane-cli-0.0.1-SHA256SUMS", []byte(strings.Replace(sums, "0", "1", 1)))
	if out, err := runInstall(); err == nil || !strings.Contains(out, "signature") {
		t.Fatalf("tampered SHA256SUMS accepted: %v\n%s", err, out)
	}
	set("/ghostlane-cli-0.0.1-SHA256SUMS", []byte(sums))
	// A tampered tarball must be refused.
	bad := append([]byte{}, tgz...)
	bad[10] ^= 0xff
	set("/ghostlane-cli-0.0.1-linux-amd64.tar.gz", bad)
	if out, err := runInstall(); err == nil || !strings.Contains(out, "checksum") {
		t.Fatalf("tampered tarball accepted: %v\n%s", err, out)
	}
}

// Reviewer finding 4: rpm runs the old package's %preun after the new %post on
// an upgrade ($1 = 1), deb runs prerm with "upgrade"; neither may stop the service.
func TestPreremoveSkipsUpgrade(t *testing.T) {
	if _, err := os.Stat("/run/systemd/system"); err != nil {
		t.Skip("the script only acts on systemd hosts")
	}
	bin := t.TempDir()
	log := filepath.Join(bin, "calls.log")
	fake := "#!/bin/sh\necho \"$*\" >> " + log + "\n"
	if err := os.WriteFile(filepath.Join(bin, "systemctl"), []byte(fake), 0o755); err != nil {
		t.Fatal(err)
	}
	run := func(arg string) string {
		_ = os.Remove(log)
		cmd := exec.Command("sh", "../../packaging/scripts/preremove.sh", arg)
		cmd.Env = append(os.Environ(), "PATH="+bin+":"+os.Getenv("PATH"))
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("%s: %v\n%s", arg, err, out)
		}
		b, _ := os.ReadFile(log)
		return string(b)
	}
	for _, upgrade := range []string{"1", "upgrade"} {
		if calls := run(upgrade); calls != "" {
			t.Fatalf("preremove on upgrade (%s) must not touch the service: %q", upgrade, calls)
		}
	}
	for _, removal := range []string{"0", "remove"} {
		if calls := run(removal); !strings.Contains(calls, "stop ghostlane.service") || !strings.Contains(calls, "disable ghostlane.service") {
			t.Fatalf("preremove on removal (%s) stops and disables: %q", removal, calls)
		}
	}
}

// Under `curl … | sh` the script is stdin and $0 is "sh": --help must still print usage.
func TestInstallHelpFromPipe(t *testing.T) {
	script, err := os.ReadFile("../../packaging/install.sh")
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sh", "-s", "--", "--help")
	cmd.Stdin = bytes.NewReader(script)
	out, err := cmd.CombinedOutput()
	if err != nil || !strings.Contains(string(out), "--base-url") || !strings.Contains(string(out), "--version") {
		t.Fatalf("%v\n%s", err, out)
	}
}

// A mirror serves one release: without --version there is nothing to look up.
func TestMirrorNeedsVersion(t *testing.T) {
	cmd := exec.Command("sh", "../../packaging/install.sh", "--base-url", "http://127.0.0.1:9", "--family", "tar", "--no-service")
	cmd.Env = append(os.Environ(), "GHOSTLANE_INSTALL_ROOT="+t.TempDir(), "GHOSTLANE_ARCH=amd64")
	out, err := cmd.CombinedOutput()
	if err == nil || !strings.Contains(string(out), "--version") {
		t.Fatalf("a mirror without --version must fail at once and say so: %v\n%s", err, out)
	}
}
