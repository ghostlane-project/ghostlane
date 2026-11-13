//go:build with_utls && !labnocores

package main

import sbtls "github.com/sagernet/sing-box/common/tls"

// The patched sing-box only (scripts/patches/sing-box-reality-hello.patch),
// as in build-cores-ios.sh: a harness that lost the replace does not compile.
var _ = sbtls.RealityAnnouncedVersion
