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
	cryptKeyV1 = "" // base64 master key of crypt1 lists (the app's OLCBOX_CRYPT_KEY_V1); empty = unavailable
)

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }
