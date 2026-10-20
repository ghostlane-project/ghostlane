# App Store: What's New

Paste the section below the line into App Store Connect, in the version's
*What's New in This Version*. English only: the listing has one language.

How it is written, because two rejections were about this text:

- It is what changed for an iPhone since the version that is **live on the
  store**, not since the last TestFlight build. The live one is in
  `curl -s "https://itunes.apple.com/lookup?bundleId=org.proofkit.app&country=us"`
  (`version`, `releaseNotes`). When this was written it was 1.0.466.
- Only what someone with an iPhone can see. Nothing about other platforms,
  and none of what the iOS build does not show.
- Not the release body and not `pending.md`: those are for every platform.

---

olcRTC
• Rooms on one meeting service hold on mobile data. There its UDP dropped out for seconds at a time, so pages crawled or stopped loading; the app now reaches that service over TCP.
• Video rooms keep their speed on a network that limits speed by dropping traffic. Such a network used to leave a room at a small part of what it carries.
• A room starts on a network where one of its service's addresses does not answer.

Server lists
• The app tells a list's provider which device it is, as other apps do, so a provider's device limit works.
• A list that turns the device away says why, in place of the servers.
