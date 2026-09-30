// Package netnstest runs a test inside a fresh network namespace: the parent
// re-executes the test binary under `unshare -n`, the child does the work.
package netnstest

import (
	"os"
	"os/exec"
	"testing"

	"github.com/vishvananda/netlink"
)

func Enter(t *testing.T) bool {
	t.Helper()
	if os.Getenv("GHOSTLANE_NETNS") != "1" {
		t.Skip("set GHOSTLANE_NETNS=1 (and run as root) for the netns tests")
	}
	if os.Geteuid() != 0 {
		t.Skip("netns tests need root")
	}
	if os.Getenv("GHOSTLANE_IN_NETNS") == "1" {
		lo, err := netlink.LinkByName("lo")
		if err != nil {
			t.Fatal(err)
		}
		if err := netlink.LinkSetUp(lo); err != nil {
			t.Fatal(err)
		}
		return true
	}
	cmd := exec.Command("unshare", "-n", "--", os.Args[0], "-test.run=^"+t.Name()+"$", "-test.v", "-test.count=1")
	cmd.Env = append(os.Environ(), "GHOSTLANE_IN_NETNS=1")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("%s failed inside the netns: %v", t.Name(), err)
	}
	return false
}
