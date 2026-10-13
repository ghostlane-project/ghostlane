
### olcRTC: MTS Link rooms

Ghostlane now connects to olcRTC rooms on MTS Link, the sixth meeting service after Yandex Telemost, WB Stream, Sber SaluteJazz, VK Calls and Jitsi. Import a link that names `mtslink`, or choose MTS Link as the service in a location's settings and paste the meeting's join link (`https://my.mts-link.ru/j/…`). An MTS Link room runs over VP8 alone. Until this release a link that named MTS Link was taken for a WB Stream room and did not connect.

### The board fits the phone it is on

On a narrow phone, or with the system's text size raised, the list of rooms lost what it was a list of. The tag, the badge, the seat count and the latency each took the width they wanted and the name had what was left — which on a 320-point screen with large text was nothing: a card that read "VP8 · YOUR SEAT" and no name. Every line of the board is now laid out name first. What does not fit beside the name goes under it: the tag and the badge on a second line, a list's buttons under its title, the sort control under the heading. Nothing on the board is cut on any width from 320 points up, at any text size up to twice the default, in English, Russian, Persian or Chinese; and on an ordinary phone at the default size a card is the one line it was.

- The status strip shows the whole of its second line, traffic and latency; the timer moves up beside the state when that line needs the room.
- On a short screen — a small phone, or any phone with large text — the status strip and the list's heading scroll with the list instead of holding a third of the screen above it.
- Text sizes above twice the default are held at twice. The wordmark and the headings grow at half the rate of the text.
- A card says how many seats are free once, beside the seats, instead of twice.
- Sheets scroll under their buttons. On a small phone with large text the disclosure's "Not now" and the camera sheet's "Continue" were below the bottom edge of the screen.
- Settings rows wrap instead of cutting their titles, settings pages scroll, and a setting's value goes under its name when the two do not fit on one line: "Mode" beside its value was one letter to a line on a narrow phone.
- "MEASURE" on a selected card is in the user's language.

### Traffic, as a chart

The line under the status said that something was moving and nothing more: received and sent were added together, scaled to the busiest second in view, with no figure to read the line against. It is now two rows of bars on one scale — received above the rule, sent below it — and the current rate of each stands in figures beside the newest bar. A bar's height follows the square root of its traffic, so a page that loads is visible beside a video that plays. It costs no more than the line did: the last 44 seconds are two fixed arrays, a second that passes redraws the bars and resets two short texts, and nothing animates in between.

The chart has its place from the press that asks for a connection, with a rule on it and two rates still to come, and the status strip takes a quarter of a second to open and to close. In 1.0.458 the chart arrived two seconds into a session, when the first second of traffic had been counted, and the list under the strip dropped by its height in one frame; the words of the strip also moved nine points sideways as the tunnel came up.

### Flags in server names

A server name that opens with a flag was cut between the flag's two letters: the card showed a boxed letter where the flag belongs, and the connect button and the status strip showed the name beginning with the other one. The flag is now taken whole, as is any emoji made of several code points.

### A folded list stays folded

Fold a server list away, leave the app, and it was unfolded on the way back in: which lists were folded was kept in the screen's own memory, and a phone throws that away with the process. It is kept with the rest of what the app knows about its lists now, on every platform, and forgotten when the list is removed — along with the list's address, which the settings used to keep after the list was gone if it had been set to choose its fastest server.

### Pinned rooms

Every card has a pin. A pinned room stands first in its own list, in the order the pins were made, above whatever the list is sorted by; and it stays on the board when its list is folded away. Fold a list of a hundred rooms and what is left is the ones that are pinned in it — the board of somebody who uses three of them. Pins are kept like folds, so they are there the next time the app opens.

A pin follows the room and not its link. An olcRTC room's link changes whenever its meeting is replaced, which a meeting service and a server do as a matter of course, and a pin that followed the link would be gone at the next fetch of the list from a room that is still there under the same name. A room is the list it is in, the service it is on and its name: one exit's rooms on different services are pinned one at a time, and a room of the same name in another list is another room.

The pin is drawn small and pressed large, forty points square, because the card around it is itself a button: a press that missed it would choose the room, and with a session up that moves the session. A card is no taller for having one.

### Google Play: Android 7 and later

The build Google Play distributes now asks for Android 7. Play began to refuse a bundle below it for apps with its automatic protection on, which is how 1.0.458 was built for Play and never arrived there. The APK on the releases page installs on Android 6 as before, and a phone can move between the two builds: they share an application id, a version code and a signing key.
