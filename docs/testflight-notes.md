# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

One change for the iPhone since 1.0.468, in olcRTC rooms.

WHAT CHANGED

• Some rooms on one meeting service could be joined only once: every connection after the first failed until the room was made anew. The app now connects to such a room, and comes back to it after a drop. The room's server needs the same fix.

WHAT TO TEST

1. Connecting again. Connect to an olcRTC server, open a page, disconnect and connect again, three times over. Do it with every olcRTC server you have: each time it must connect and the page must load. A server whose room took one connection and then failed every time in 1.0.468 should connect now, each time; if it still fails, the room's server may not have the same fix yet.

2. Staying connected. Stay connected to an olcRTC server for ten minutes and use the phone as usual: pages must keep loading.

3. Other servers. Connect to a server of another kind (Reality, Hysteria2) and open a few pages: they must load as they did in 1.0.468.
