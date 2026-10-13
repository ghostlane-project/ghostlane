# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

This build is about fitting the screen and keeping your rooms in reach: small iPhones, Display Zoom and Larger Text; rooms you can pin; a screen that is as you left it when you come back; a traffic chart that counts every second.

WHAT CHANGED

• The list of rooms is laid out name first. On a narrow screen or with large text a room's name could be squeezed to nothing. What does not fit beside the name now goes on the next line.
• Every room has a pin. A pinned room moves to the top of its list and stays on the screen when the list is folded away.
• The screen is as you left it. A list you folded, the transport you chose in the chips above the list and the place you had scrolled to are all still there when you come back to the app. The transport and the folded lists also survive closing the app.
• Traffic under the connection status is a chart: download bars above the line, upload bars below it, with the current speed of each in figures. It now counts every second; the last build drew one bar in three and "0 B/s" most of the time.
• The chart and the totals are kept while you are in another app, and the totals no longer restart after 4 GB.
• The status opens once, when you press connect, and keeps its height.
• On a short screen (a small iPhone, or any iPhone with large text) the status and the list's heading scroll with the list.
• Sheets and settings pages scroll and wrap, so no button ends up below the edge of the screen.
• A flag at the start of a server's name is drawn as a flag.

WHAT TO TEST

1. Large text. Settings → Display & Brightness → Text Size, then Accessibility → Display & Text Size → Larger Text. Raise the size step by step to the largest and look at the list each time. Every room must show its whole name. Do the same with Display Zoom set to Larger Text, and on the smallest iPhone you have.

2. Pins. Press the pin on a room in the middle of a long list. The room must move to the top of its list, a line at the bottom of the screen must say so, and the room you are connected to must not change. Press the pin again and the room goes back.

3. Folding. Pin two or three rooms, then fold the list with the arrow beside its name: only the pinned rooms stay. Close the app from the app switcher and open it again: the list is still folded and the rooms are still pinned. Pull down to refresh the list: the pins stay on the same rooms.

4. Coming back. Choose a transport in the chips above the list (Reality, Hysteria2, a kind of olcRTC room) and scroll the list down. Go to the Home Screen or another app, wait a few seconds and come back: the same transport must be chosen and the list must be where you left it. Then close the app from the app switcher and open it again: the transport must still be chosen.

5. Connecting. Press connect and watch the top of the screen. The status must open once, smoothly, and nothing under it may jump a moment later.

6. The chart. Stay connected for a minute while you load a page, play a video and upload a photo. There must be a bar for every second that something moved, with no regular gaps between bars, and the two speeds must follow what you do. Leave the app for ten seconds during a download and come back: the chart must still have its earlier bars, with the time you were away filled in, and the phone must not get warm.

7. Sheets. With large text, open the + button, the clock button (diagnostics), Settings and each page under it, and Settings → Replay first run. Every button must be visible or reachable by scrolling.

8. Flags. If the names in your server list start with flags, the flag must be drawn on the card, and the name on the connect button must not start with a boxed letter.

9. iPad. Rotate it and use Split View: nothing may be cut in either pane.

HOW TO REPORT

Take a screenshot and choose Share Beta Feedback, or use Send Beta Feedback in the TestFlight app. Please say which iPhone or iPad it is and which text size you had set.
