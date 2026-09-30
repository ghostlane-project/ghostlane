package pins

import "testing"

func TestGoModMatchesRepoPins(t *testing.T) {
	repo, err := FromRepo("../../..")
	if err != nil {
		t.Fatal(err)
	}
	mod, err := FromGoMod("../../go.mod")
	if err != nil {
		t.Fatal(err)
	}
	if repo.Engine != mod.Engine {
		t.Fatalf("engine pin: scripts/cores-pins.sh says %q, cli/go.mod replaces with %q", repo.Engine, mod.Engine)
	}
	if "v"+repo.SingBox != mod.SingBox {
		t.Fatalf("sing-box pin: release.yml says %q, cli/go.mod requires %q", repo.SingBox, mod.SingBox)
	}
	// Xray-core at the version the iOS Cores link through libxray v1.260711.0.
	if mod.Xray != "v1.260327.1-0.20260711155151-50231eaff98c" {
		t.Fatalf("xray-core pin: cli/go.mod requires %q", mod.Xray)
	}
}
