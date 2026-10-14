# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

Two changes for the iPhone since 1.0.460: what the app does when it opens, it now does once; and servers named by a country's code get that country's flag.

WHAT CHANGED

• "Connect on launch", "Update on open" and "Ping on launch" ran again every time you came back to the app. With "Connect on launch" on, if you disconnected, looked at another app and came back, the app connected again by itself. They now run once for each time the app is started.
• A server named by its country's code (DE Direct, GB · olcRTC) is shown with the country's flag in front. Until now a flag was shown only when the server's name already had one in it.

WHAT TO TEST

1. Connect on launch. In Settings → Server lists, turn "Connect on launch" on. Close the app from the app switcher and open it: it must connect by itself. Now disconnect, go to the Home Screen or another app, wait ten seconds and come back: it must stay disconnected. Lock the phone, unlock it and look again: still disconnected.

2. Update on open and Ping on launch. Turn both on in the same place, close the app from the app switcher and open it: the server list must refresh once and the latencies must be measured once. Leave the app and come back a few times: nothing may refresh or measure again by itself.

3. Flags. Look at a server list whose names begin with a country's code: each such server must have the flag of that country in front of its name, and the name itself must be as it was. A server named after something that is not a country must have no flag. Pins, the order of the list and the selected server must be as before.
