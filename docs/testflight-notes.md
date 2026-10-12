# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

This build is about fitting the screen: small iPhones, Display Zoom, and
Larger Text. It also draws traffic as a chart and opens rooms on one more
meeting service.

WHAT CHANGED

• The list of rooms is laid out name first. On a narrow screen or with
  large text a room's name could be squeezed to nothing between its tag,
  its badge and its latency. What does not fit beside the name now goes on
  the next line; at the default text size on a regular iPhone a card is
  the one line it was.
• On a short screen (a small iPhone, or any iPhone with large text) the
  status and the list's heading scroll with the list.
• Text sizes above twice the default are shown at twice the default.
• Sheets and settings pages scroll and wrap. The disclosure's "Not now"
  and the camera sheet's "Continue" could end up below the screen's edge.
• Under the connection status, traffic is a chart: download bars above
  the line, upload bars below it, the current speed of each in figures.
• A flag at the start of a server's name is a flag, not a boxed letter.
• olcRTC rooms on one more meeting service connect.

WHAT TO TEST

1. Settings → Display & Brightness → Text Size, and Accessibility →
   Display & Text Size → Larger Text: raise the size step by step, up to
   the largest, and look at the list each time. Every room must show its
   whole name. Tell us the phone and the setting if anything is cut,
   overlaps, or runs off the screen.
2. The same with Display Zoom set to "Larger Text" (Settings → Display &
   Brightness → Display Zoom), and on the smallest iPhone you have.
3. With large text, open every sheet: the + button, the clock button
   (diagnostics), Settings and each page under it, and the first-run
   walkthrough (Settings → "Replay first run"). Every button must be
   visible or reachable by scrolling.
4. Connect and watch the chart for a minute while you load a page, play
   a video and upload a photo. The bars must follow what you do, the two
   figures must read as speeds that make sense, and the phone must not
   get warm with the app open.
5. A server list whose names start with flags: the flag must be drawn on
   the card, and the name on the button must not start with a boxed letter.
6. If your server list has a line for the new meeting service: it must
   connect and carry traffic like the other rooms.
7. Rotate an iPad, and use Split View: nothing may be cut in either pane.
