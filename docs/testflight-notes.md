# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

Four changes for the iPhone since 1.0.466: three in olcRTC rooms and one in server lists.

WHAT CHANGED

• Rooms on the meeting service that 1.0.466 added hold on mobile data. There its UDP dropped out for seconds at a time, so pages crawled or stopped loading; the app now reaches that service over TCP.
• Video rooms keep their speed on a network that limits speed by dropping traffic. Such a network used to leave a room at a small part of what it carries.
• A room starts on a network where one of its service's addresses does not answer.
• The app tells a server list's provider which device it is, as other apps do, so a provider's device limit works. A list that turns the device away is kept with the reason in place of its servers, and the connection you are on keeps running.

WHAT TO TEST

1. Mobile data. Turn Wi-Fi off. Connect to an olcRTC server on the meeting service that 1.0.466 added, then open a few pages and play a video for a few minutes: the pages must load, and the video must not stop for seconds at a time.

2. Wi-Fi. Turn Wi-Fi on and do the same with the same server: it must be as fast as before.

3. Other olcRTC servers. Connect to an olcRTC server on another meeting service and open a few pages: they must load as they did in 1.0.466.

4. Server lists. Refresh a server list you already have: it must come back as before. If you have a list from a provider that counts devices, add it on this iPhone and then on a second device past the limit: the second one must show the list with no servers and the sentence that the list has no place for this device, and this iPhone must keep its servers after a refresh and while connected. If the list turned away held the server selected on the second device, its home screen must ask you to pick a server, and its button must take you to the list, not to adding one.
