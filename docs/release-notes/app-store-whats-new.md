# App Store: What's New

Paste the section below the line into App Store Connect, in the version's
*What's New in This Version*. English only: the listing has one language.

How it is written, because two rejections were about this text:

- It is what changed for an iPhone since the version that is **live on the
  store**, not since the last TestFlight build. The live one is in
  `curl -s "https://itunes.apple.com/lookup?bundleId=org.proofkit.app&country=us"`
  (`version`, `releaseNotes`). When this was written it was 1.0.468.
- Only what someone with an iPhone can see. Nothing about other platforms,
  and none of what the iOS build does not show.
- Not the release body and not `pending.md`: those are for every platform.

---

Servers
• VLESS Reality servers running a new Xray version refused the app's connection. The app now connects to them, and to older servers as before.
• A server link that names its fingerprint or flow as Xray does no longer stops the connection.

Server lists
• At launch the server list is on screen at once, and Connect does not wait, while a list is updated from an address that is slow to answer or does not answer.
• The app waits for its saved lists and settings when they cannot be read yet, as on a phone just restarted, instead of starting as if newly installed.

olcRTC
• Some rooms on one meeting service could be joined only once: every connection after the first failed until the room was made anew. The app now connects to such a room, and comes back to it after a drop. The room's server needs the same fix.
