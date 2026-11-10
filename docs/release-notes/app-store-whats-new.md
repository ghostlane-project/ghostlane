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

olcRTC
• Some rooms on one meeting service could be joined only once: every connection after the first failed until the room was made anew. The app now connects to such a room, and comes back to it after a drop. The room's server needs the same fix.
