# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

Two changes for the iPhone since 1.0.469, both in the server lists.

WHAT CHANGED

• An update of the server lists no longer holds the app up while it downloads. At launch the list is on screen at once, and Connect, a choice of server and a removed list do not wait, also when a list's address is slow to answer or does not answer.

• The app waits for its saved lists and settings when they cannot be read yet, as on a phone just restarted and not yet unlocked, instead of starting as if newly installed. Its store and its screens are now made with the first screen.

WHAT TO TEST

1. Launch. Close the app fully and open it again, five times over, on Wi-Fi and on mobile data, with the VPN off: each time your server lists must be on screen at once, with the server you chose still chosen, and no intro.

2. Connect right after launch. Open the app and press Connect at once: it must start connecting without a pause of many seconds.

3. During an update. Press the arrow that updates a list and, while it turns, choose another server: your choice must stay, and a running tunnel must move to it once, not twice. Then update again and remove another list while it turns: the removed list must stay removed.

4. After a restart of the phone. Restart the phone, unlock it, wait a minute and open the app: your lists, your chosen server and your settings must be there, and no intro. If the intro or an empty list shows, note what you did and when.

5. Everything else. Add a list by link and by QR code, connect to a server of each kind you have (olcRTC, Reality, Hysteria2) and open a few pages: all as in 1.0.469.
