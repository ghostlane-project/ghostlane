### Jitsi rooms on self-hosted servers

A Jitsi server installed with docker names its XMPP host `meet.jitsi`, whatever its web address. The app reads that name from the site's `config.js`; when the file did not load (a network hiccup, a protection page, a 403), it silently used the web address instead, and the server refused the connection with `host-unknown … This server does not serve <site>` (#22, seen on iOS). `config.js` is now fetched like a browser does, retried within a 10-second budget, and when it still cannot be read and the server answers `host-unknown`, the app joins once more under `meet.jitsi`. If a join still fails, the error says why `config.js` was unavailable.

### WB Stream over mobile data

The library behind WB Stream gave the whole room join, signalling and the call's own connection together, only 5 seconds. That is enough on Wi-Fi and often not on mobile data, where the call's connection has taken over 20 seconds: every WB Stream room failed with `could not connect after timeout` while the same room worked over Wi-Fi (#38). The join now has 25 seconds, and leaving or stopping during a join no longer waits for it. Android and the desktop app wait up to 35 seconds for an olcRTC room to come up, as iOS already did, and a room check waits up to 30 seconds instead of 8.

### Every release is tested on real rooms first

Before any build starts, the release now runs the engine it ships against real Telemost, WB Stream and Jitsi rooms, with the phone's own build of the engine and the desktop's: connecting, downloads and uploads under load, bursts of new connections, DNS, a quiet period after load, and the phone's memory. The report is attached to the release. Android, desktop and iOS now build the same engine commit, the one the report is about.
