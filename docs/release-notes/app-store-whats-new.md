# App Store: What's New

Paste the section below the line into App Store Connect, in the version's
*What's New in This Version*. English only: the listing has one language.

How it is written, because two rejections were about this text:

- It is what changed for an iPhone since the version that is **live on the
  store**, not since the last TestFlight build. The live one is in
  `curl -s "https://itunes.apple.com/lookup?bundleId=org.proofkit.app&country=us"`
  (`version`, `releaseNotes`). When this was written it was 1.0.444.
- Only what someone with an iPhone can see. Nothing about other platforms,
  and none of what the iOS build does not show.
- Not the release body and not `pending.md`: those are for every platform.

---

Privacy
• On a network that uses IPv6, connections to IPv6 addresses could go outside the tunnel while the VPN showed as connected. The tunnel now takes IPv6 as well, so everything goes through it.
• New in Settings › Connection: a kill switch, off by default. With it on, when the VPN is not up iOS holds your traffic instead of sending it directly, and brings the VPN back by itself. Please read the note under the switch before you turn it on.

Servers
• Server links that use the HTTP transport now connect.
• VLESS servers with ordinary TLS, with a self-signed certificate, or without TLS now connect as their link says.
