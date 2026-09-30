// ghostlane is GPL-3.0-or-later: it links sing-box. See cli/LICENSE.
package main

import (
	"os"

	_ "github.com/xtls/xray-core/main/distro/all" // XHTTP: every Xray feature registered
)

// Set by -ldflags at build time (see Makefile).
var (
	version    = "dev"
	enginePin  = "unknown"
	singboxPin = "unknown"
	xrayPin    = "unknown"
)

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }
