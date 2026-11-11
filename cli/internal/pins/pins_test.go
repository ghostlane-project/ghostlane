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
	// The tree every build compiles is the pinned version with the Reality
	// hello patch (scripts/patched-singbox.sh): a release.yml that moves on
	// without it would build one sing-box and test another.
	if repo.SingBoxPatched != repo.SingBox {
		t.Fatalf("sing-box pin: release.yml says %q, scripts/cores-pins.sh (the patched tree) %q", repo.SingBox, repo.SingBoxPatched)
	}
	if mod.SingBoxPatched != "./.sing-box-patched" {
		t.Fatalf("cli/go.mod must replace sing-box with ./.sing-box-patched (make singbox-src), not %q", mod.SingBoxPatched)
	}
	// Xray-core at the version the iOS Cores link through libxray v1.260711.0.
	if mod.Xray != "v1.260327.1-0.20260711155151-50231eaff98c" {
		t.Fatalf("xray-core pin: cli/go.mod requires %q", mod.Xray)
	}
}
