//go:build with_utls

package singbox

import sbtls "github.com/sagernet/sing-box/common/tls"

// The sing-box linked here must be the patched tree (cli/go.mod replaces the
// module with it; scripts/patches/sing-box-reality-hello.patch): its Reality
// hello keeps the X25519MLKEM768 key share and announces a version current
// Xray servers accept. RealityAnnouncedVersion exists only in that tree, so a
// build against an unpatched sing-box stops here rather than shipping a
// client those servers refuse.
var _ = sbtls.RealityAnnouncedVersion
