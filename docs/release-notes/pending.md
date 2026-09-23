### SaluteJazz holds up under heavy downloads

On a slow network a long download over SaluteJazz could stall the whole connection until it dropped: Sber's service queues everything one side sends, and the connection's own keep-alive waited at the back of that queue. The app and the servers now pace what they send through it, so the keep-alive always gets through. The servers made the switch on 23 September; this release is the app's half.

### Android: calls and games through the tunnel

On Android the tunnel carried no UDP at all, on every protocol: voice and video calls in Telegram, WhatsApp and Discord, games and anything else on UDP failed while web pages worked. The tunnel now hands UDP to the connection the standard way, as the iPhone app always has.

### iPhone and iPad: each carrier keeps its own rooms

A location that offers Telemost, WB Stream and SaluteJazz lists one room per carrier. After a network change the app could hand a WB Stream or SaluteJazz connection a Telemost room, which it cannot join, and reconnect slowly or not at all. Each carrier's line now keeps to its own rooms.
