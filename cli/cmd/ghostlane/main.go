// ghostlane is GPL-3.0-or-later: it links sing-box. See cli/LICENSE.
package main

import (
	"fmt"
	"os"

	_ "github.com/openlibrecommunity/olcrtc/mobile"
	_ "github.com/sagernet/sing-box/include"
)

// Set by -ldflags at build time (see Makefile).
var (
	version    = "dev"
	enginePin  = "unknown"
	singboxPin = "unknown"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "version" {
		fmt.Printf("ghostlane %s\nengine %s\nsing-box %s\n", version, enginePin, singboxPin)
		return
	}
	fmt.Fprintln(os.Stderr, "usage: ghostlane version")
	os.Exit(2)
}
