# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

This build is about fitting the screen and keeping your rooms in reach: small iPhones, Display Zoom and Larger Text; rooms you can pin; a connection status that no longer jumps.

WHAT CHANGED

• The list of rooms is laid out name first. On a narrow screen or with large text a room's name could be squeezed to nothing. What does not fit beside the name now goes on the next line.
• Every room has a pin. A pinned room moves to the top of its list and stays on the screen when the list is folded away.
• A list you folded is still folded when you open the app again.
• Traffic under the connection status is a chart: download bars above the line, upload bars below it, with the current speed of each in figures.
• The status opens once, when you press connect, and keeps its height.
• On a short screen (a small iPhone, or any iPhone with large text) the status and the list's heading scroll with the list.
• Sheets and settings pages scroll and wrap, so no button ends up below the edge of the screen.
• A flag at the start of a server's name is drawn as a flag.

WHAT TO TEST

1. Large text. Settings → Display & Brightness → Text Size, then Accessibility → Display & Text Size → Larger Text. Raise the size step by step to the largest and look at the list each time. Every room must show its whole name. Do the same with Display Zoom set to Larger Text, and on the smallest iPhone you have.

2. Pins. Press the pin on a room in the middle of a long list. The room must move to the top of its list, a line at the bottom of the screen must say so, and the room you are connected to must not change. Press the pin again and the room goes back.

3. Folding. Pin two or three rooms, then fold the list with the arrow beside its name: only the pinned rooms stay. Close the app from the app switcher and open it again: the list is still folded and the rooms are still pinned. Pull down to refresh the list: the pins stay on the same rooms.

4. Connecting. Press connect and watch the top of the screen. The status must open once, smoothly, and nothing under it may jump a moment later.

5. The chart. Stay connected for a minute while you load a page, play a video and upload a photo. The bars must follow what you do, the two speeds must make sense, and the phone must not get warm.

6. Sheets. With large text, open the + button, the clock button (diagnostics), Settings and each page under it, and Settings → Replay first run. Every button must be visible or reachable by scrolling.

7. Flags. If the names in your server list start with flags, the flag must be drawn on the card, and the name on the connect button must not start with a boxed letter.

8. iPad. Rotate it and use Split View: nothing may be cut in either pane.

HOW TO REPORT

Take a screenshot and choose Share Beta Feedback, or use Send Beta Feedback in the TestFlight app. Please say which iPhone or iPad it is and which text size you had set.
