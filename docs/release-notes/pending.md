### iPhone: connecting over mobile data on more carriers

A phone on mobile data often has several cellular connections open at once: the one to the internet, and others the carrier keeps for calls and messages. On some carriers the app sent its own traffic through one of the carrier's service connections, so on mobile data every location failed to connect ("no route to host") while Wi-Fi worked. The app now uses the connection iOS itself uses for the internet.

### olcRTC on SaluteJazz: holds on a slow connection

On a slow SaluteJazz connection the app now keeps only as much data in flight as the connection has recently carried, instead of a fixed amount. A long upload no longer holds up the small messages that keep the connection alive, so it stays up instead of dropping and reconnecting. Servers running the updated engine do the same for downloads.
