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
}
