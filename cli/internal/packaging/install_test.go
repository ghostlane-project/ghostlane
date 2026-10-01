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

// fakeInit puts recording stand-ins for the init tools on PATH and returns the
// directory and the log they write to.
func fakeInit(t *testing.T, names ...string) (bin, log string) {
	t.Helper()
	bin = t.TempDir()
	log = filepath.Join(bin, "calls.log")
	for _, n := range names {
		fake := "#!/bin/sh\necho \"" + n + " $*\" >> " + log + "\n"
		if err := os.WriteFile(filepath.Join(bin, n), []byte(fake), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	return bin, log
}

// On an OpenRC host the package scripts enable and start the service with
// rc-update/rc-service, and a removal (not an upgrade) stops and disables it.
func TestPackageScriptsOpenRC(t *testing.T) {
	bin, log := fakeInit(t, "rc-update", "rc-service", "systemctl")
	openrc := t.TempDir() // stands for /run/openrc
	run := func(script string, args ...string) string {
		_ = os.Remove(log)
		cmd := exec.Command("sh", append([]string{"../../packaging/scripts/" + script}, args...)...)
		cmd.Env = append(os.Environ(), "PATH="+bin+":"+os.Getenv("PATH"), "GHOSTLANE_SYSTEMD_DIR=/nonexistent/systemd", "GHOSTLANE_OPENRC_DIR="+openrc, "GHOSTLANE_INIT_SCRIPT=../../packaging/openrc/ghostlane")
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("%s %v: %v\n%s", script, args, err, out)
		}
		b, _ := os.ReadFile(log)
		return string(b)
	}
	// the fake's "status" succeeds, so the script restarts rather than starts
	if calls := run("postinstall.sh"); !strings.Contains(calls, "rc-update add ghostlane default") || !strings.Contains(calls, "rc-service ghostlane restart") || strings.Contains(calls, "systemctl") {
		t.Fatalf("postinstall on OpenRC: %q", calls)
	}
	for _, upgrade := range []string{"1", "upgrade"} {
		if calls := run("preremove.sh", upgrade); calls != "" {
			t.Fatalf("preremove on upgrade (%s): %q", upgrade, calls)
		}
	}
	if calls := run("preremove.sh", "0"); !strings.Contains(calls, "rc-service ghostlane stop") || !strings.Contains(calls, "rc-update del ghostlane default") {
		t.Fatalf("preremove on removal: %q", calls)
	}
}

// The tarball path installs the OpenRC script where openrc-run exists.
func TestInstallScriptTarballOpenRC(t *testing.T) {
	root := t.TempDir()
	srv := newAssetServer(t)
	tgz := tarball(t, map[string]string{"ghostlane": "#!/bin/sh\necho ghostlane 0.0.1\n", "ghostlane.service": "[Unit]\nExecStart=/usr/bin/ghostlane run\n", "ghostlane.openrc": "#!/sbin/openrc-run\ncommand=/usr/bin/ghostlane\n", "README.md": "x", "LICENSE": "y", "COPYRIGHT": "z"})
	srv.set("/ghostlane-cli-0.0.1-linux-amd64.tar.gz", tgz)
	srv.sign(t)
	bin, _ := fakeInit(t, "openrc-run", "rc-update", "rc-service")
	cmd := exec.Command("sh", "../../packaging/install.sh", "--version", "0.0.1", "--base-url", srv.URL, "--family", "tar", "--no-service")
	cmd.Env = append(os.Environ(), "PATH="+bin+":"+os.Getenv("PATH"), "GHOSTLANE_INSTALL_ROOT="+root, "GHOSTLANE_PUBKEY_FILE="+srv.pubFile, "GHOSTLANE_ARCH=amd64")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%v\n%s", err, out)
	}
	st, err := os.Stat(filepath.Join(root, "etc/init.d/ghostlane"))
	if err != nil || st.Mode().Perm()&0o111 == 0 {
		t.Fatalf("init script: %v %v", st, err)
	}
	if b, _ := os.ReadFile(filepath.Join(root, "etc/init.d/ghostlane")); !strings.Contains(string(b), "command=/usr/local/bin/ghostlane") {
		t.Fatalf("the script points at the installed binary: %s", b)
	}
}

func TestOpenRCScript(t *testing.T) {
	b, err := os.ReadFile("../../packaging/openrc/ghostlane")
	if err != nil {
		t.Fatal(err)
	}
	if out, err := exec.Command("sh", "-n", "../../packaging/openrc/ghostlane").CombinedOutput(); err != nil {
		t.Fatalf("%v\n%s", err, out)
	}
	for _, want := range []string{"#!/sbin/openrc-run", "supervisor=supervise-daemon", "command=/usr/bin/ghostlane", `command_args="run"`, `command_user="ghostlane:ghostlane"`, `capabilities="^cap_net_admin,^cap_net_bind_service"`, "checkpath -d -m 0750 -o ghostlane:ghostlane /run/ghostlane"} {
		if !strings.Contains(string(b), want) {
			t.Fatalf("missing %q", want)
		}
	}
}

// assetServer serves a release's assets, signed with a throwaway key.
type assetServer struct {
	*httptest.Server
	mu      sync.Mutex
	assets  map[string][]byte
	priv    ed25519.PrivateKey
	pubFile string
}

func newAssetServer(t *testing.T) *assetServer {
	t.Helper()
	if _, err := exec.LookPath("openssl"); err != nil {
		t.Skip("openssl missing")
	}
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(pub)
	pubFile := filepath.Join(t.TempDir(), "pub.pem")
	_ = os.WriteFile(pubFile, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}), 0o644)
	a := &assetServer{assets: map[string][]byte{}, priv: priv, pubFile: pubFile}
	a.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		b, ok := a.assets[r.URL.Path]
		a.mu.Unlock()
		if !ok {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write(b)
	}))
	t.Cleanup(a.Close)
	return a
}

func (a *assetServer) set(path string, b []byte) { a.mu.Lock(); a.assets[path] = b; a.mu.Unlock() }

// sign writes SHA256SUMS over every asset of the release and its signature.
func (a *assetServer) sign(t *testing.T) {
	t.Helper()
	a.mu.Lock()
	defer a.mu.Unlock()
	var sums string
	for path, b := range a.assets {
		if strings.Contains(path, "SHA256SUMS") {
			continue
		}
		sum := sha256.Sum256(b)
		sums += hex.EncodeToString(sum[:]) + "  " + strings.TrimPrefix(path, "/") + "\n"
	}
	a.assets["/ghostlane-cli-0.0.1-SHA256SUMS"] = []byte(sums)
	a.assets["/ghostlane-cli-0.0.1-SHA256SUMS.sig"] = ed25519.Sign(a.priv, []byte(sums))
}
