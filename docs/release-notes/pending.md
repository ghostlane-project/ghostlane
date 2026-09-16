### olcRTC survives losing the network

An eight-hour olcRTC session on cellular died 45 seconds after the signal vanished, and not for want of a memory limit: with no interface to dial from, every connection the phone's apps retried sat in the tunnel waiting for a room that could not come back, each one a session in the tun2socks outside the Go heap, until the extension hit its 50 MB ceiling. Three changes, all on the client side:

- The engine now parks at most 64 requests on a missing session; the rest are refused at once with "network unreachable", which apps take as a reason to back off.
- A reconnect no longer opens a hundred sockets to discover that there is no route. One probe socket every few seconds says when the network is back, and the wait does not count against the attempts that would otherwise end the session for good.
- The packet extension remembers "no interface at all" for five seconds instead of walking the interfaces again for every socket, so the diagnostics show one line instead of a storm.

Fixes #37. Nothing else in the cores changed.
