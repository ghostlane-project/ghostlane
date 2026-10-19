# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

Three changes for the iPhone since 1.0.466, all in olcRTC rooms.

WHAT CHANGED

• Rooms on the meeting service that 1.0.466 added hold on mobile data. There its UDP dropped out for seconds at a time, so pages crawled or stopped loading; the app now reaches that service over TCP.
• Video rooms keep their speed on a network that limits speed by dropping traffic. Such a network used to leave a room at a small part of what it carries.
• A room starts on a network where one of its service's addresses does not answer.

WHAT TO TEST

1. Mobile data. Turn Wi-Fi off. Connect to an olcRTC server on the meeting service that 1.0.466 added, then open a few pages and play a video for a few minutes: the pages must load, and the video must not stop for seconds at a time.

2. Wi-Fi. Turn Wi-Fi on and do the same with the same server: it must be as fast as before.

3. Other olcRTC servers. Connect to an olcRTC server on another meeting service and open a few pages: they must load as they did in 1.0.466.
