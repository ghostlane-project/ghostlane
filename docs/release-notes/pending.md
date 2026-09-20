### The engine behind the rooms got a hard look

Everything below is one release of the transport engine, found by running real
transfers through real relays instead of trusting that they work.

**Jitsi rooms carry data again, and keep carrying it.** The app never told the
videobridge how our side was receiving, so the bridge decided we were congested,
dropped its estimate to its floor and stopped forwarding video to us, while our
own side kept sending. Transfers stalled with the connection still alive. We send
that feedback now, and a transfer that used to hang for minutes finishes in
seconds.

**A room recovers by itself after the relay drops it.** WB Stream removes a
participant that publishes above about 12 Mbit/s for half a minute, which is what
a busy download does. The app now stays under that ceiling, and when a relay does
drop the server, the connection comes back in seconds instead of three minutes.

**Bursts of new connections stop going missing.** Opening many connections at once,
what a page full of images or a burst of name lookups does, sent hundreds of tiny
messages that the bridge's queue threw away. They travel together now.

**Large downloads through a busy room stop breaking.** A slow receiver made the
bridge drop what did not fit its buffer, and six parallel downloads were enough.
Each sender now keeps a bounded amount in flight per receiver.

**The fallback transports work.** The one that hides data inside video frames was
sending a single message per round trip, so a 15 MiB transfer could not finish in
five minutes; it now keeps a window in flight and finishes in under half a minute.
The QR-style transport was drawing its squares off the video codec's grid, so whole
frames were unreadable, and it answered none of the bridge's requests to resend a
lost packet.

**Reconnecting works on Jitsi.** A bridge that reconnected never asked for the
peer's video again, and a client that rejoined was never recognised, so any
network change killed the tunnel until the app restarted.

Nothing here changes how the app is used, and an older peer still connects to a
newer one.
