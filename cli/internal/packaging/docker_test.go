package packaging

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// stage.sh lays the released tarballs' binaries out as Docker's TARGETPLATFORM
// paths, so the image holds exactly what the release shipped.
func TestDockerStage(t *testing.T) {
	dist := t.TempDir()
	for arch, bin := range map[string]string{"amd64": "A", "arm64": "B", "arm7": "C"} {
		tgz := tarball(t, map[string]string{"ghostlane": bin, "ghostlane.service": "u", "README.md": "r"})
		if err := os.WriteFile(filepath.Join(dist, "ghostlane-cli-0.0.1-linux-"+arch+".tar.gz"), tgz, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	out, err := exec.Command("sh", "../../packaging/docker/stage.sh", dist, "0.0.1").CombinedOutput()
	if err != nil {
		t.Fatalf("%v\n%s", err, out)
	}
	for platform, want := range map[string]string{"linux/amd64": "A", "linux/arm64": "B", "linux/arm/v7": "C"} {
		p := filepath.Join(dist, "docker", platform, "ghostlane")
		b, err := os.ReadFile(p)
		if err != nil || string(b) != want {
			t.Fatalf("%s: %q %v", platform, b, err)
		}
		if st, _ := os.Stat(p); st.Mode().Perm()&0o111 == 0 {
			t.Fatalf("%s: not executable", platform)
		}
	}
	// a missing tarball is an error, not a silent gap in the image
	_ = os.Remove(filepath.Join(dist, "ghostlane-cli-0.0.1-linux-arm64.tar.gz"))
	if out, err := exec.Command("sh", "../../packaging/docker/stage.sh", dist, "0.0.1").CombinedOutput(); err == nil || !strings.Contains(string(out), "arm64") {
		t.Fatalf("missing tarball: %v\n%s", err, out)
	}
}
