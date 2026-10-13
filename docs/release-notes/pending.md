### macOS: names are looked up through the tunnel

In "System-wide tunnel" on a Mac whose resolver is the router, which is most home and office networks, every name the system looked up went to the router beside the tunnel, in the clear, and the address the router answered with was the one the connection then used. The traffic was in the tunnel and the names were not. A router's address is on the local link, and no route of a tunnel outranks that. On a Mac with a public resolver set by hand the questions did enter the tunnel, which is how this went unseen since the mode shipped.

- While a tunnel runs, the system asks an address inside it, and the tunnel answers: the name is carried to the exit and resolved there, on every kind of server, as it already was in an olcRTC room. The tunnel helper sets this and takes it away the moment the tunnel is gone, however it went.
- The local network's own names (`.lan`, `.home`, `.local`, `.internal` and their like, and a name with no dot in it) are still asked of the local network.
- A server chosen later in a session, and an olcRTC engine that signs in again, ask the tunnel for their own names and get real addresses.
- Not moved: a lookup an application ties to an interface itself, and encrypted DNS an application or a profile does on its own. Proxy mode has no tunnel and no part of this.

This needs the helper that comes with this build. An older one that is still running is replaced at the first connect after the update; the log says "the tunnel helper was updated".

### macOS: a kill switch

Settings → Connection → **Kill switch**, on a Mac whose tunnel helper is installed. Off unless switched on.

On a Mac the tunnel's interface and every route through it belong to the tunnel's process and go with it. When that process died, the Mac's traffic went out directly, at its own address, at once, and the app noticed some eight seconds later. An app that was killed, or a server's own process dying, never did this: the tunnel stayed and led nowhere.

- With the switch on, the tunnel is started behind a block in the system's packet filter, held by the tunnel helper. If the tunnel's process stops, traffic is refused instead of going out directly, and the app says "The tunnel is down and the kill switch is blocking traffic". Connect again, or turn the switch off, to unblock it. Neither asks for a password.
- The local network stays reachable, as it always was in this mode: a printer, a share, AirDrop. No name is asked of the router while the block stands.
- A session that is restarted in full, for another routing mode, keeps the block across the restart: the new tunnel comes up behind it. Without the switch the Mac's traffic goes out directly in between, as before. Changed to proxy mode, the block goes with the tunnel.
- An olcRTC room works under it on every carrier. The room's engine sends its media from the Mac's own network address, which macOS routes beside any tunnel, so the block has to let that one program out and nothing else: the tunnel helper runs the engine for the app. The room is joined first and the tunnel started after it, as without the switch.
- Turning the switch on takes effect at the next connection; turning it off, at once.
- It holds every program's traffic that would have reached the internet beside the tunnel, another VPN's included. It does not outlast a restart of the Mac, and it is taken away when the helper is switched off in Login Items or the app is deleted.

Windows still has no kill switch: nothing there owns a filter that outlives the tunnel.

### What the app does when it opens, it does once

"Connect on launch", "refresh on open" and "measure on launch" ran at every return to the app on an iPhone, where the screen is built again each time. With "connect on launch" on, somebody who disconnected, looked at another app and came back was connected again by an app that had been running all along. They run once for each time the app is started, on every platform.
