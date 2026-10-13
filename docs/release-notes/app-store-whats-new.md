# App Store: What's New

Paste the section below the line into App Store Connect, in the version's
*What's New in This Version*. English only: the listing has one language.

How it is written, because two rejections were about this text:

- It is what changed for an iPhone since the version that is **live on the
  store**, not since the last TestFlight build. The live one is in
  `curl -s "https://itunes.apple.com/lookup?bundleId=org.proofkit.app&country=us"`
  (`version`, `releaseNotes`). When this was written it was 1.0.456.
- Only what someone with an iPhone can see. Nothing about other platforms,
  and none of what the iOS build does not show.
- Not the release body and not `pending.md`: those are for every platform.

---

Design
• The list of rooms now fits small iPhones and large text. On a narrow screen, or with Larger Text on, a room's name could be squeezed down to nothing. The name now comes first, and what does not fit beside it moves to the next line.
• On a short screen the status and the list's heading scroll with the list, so the list has the screen.
• Sheets and settings pages scroll and wrap. A button that large text had pushed below the edge of the screen can always be reached.

Servers
• Pin a room to the top of its list with the pin on its card. Fold the list away and the pinned rooms stay on the screen.
• The screen is as you left it when you come back: a list you folded, the transport you chose and your place in the list.
• A flag at the start of a server's name is shown as a flag.
• olcRTC rooms on one more meeting service now connect.

Traffic
• The line under the connection status is now a chart: download and upload as separate bars on one scale, second by second, with the current speed of each in figures.
