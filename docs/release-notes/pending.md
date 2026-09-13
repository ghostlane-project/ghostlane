### Speed tests on iOS

The tunnel no longer stops during a speed test on iOS. The extension that carries the tunnel has about 50 MB to work with; on the xhttp path one of the engines had been quietly lowering that ceiling below what the runtime needs, and the upload phase of a speed test buffered several megabytes per connection. Both are fixed. Uploads on a single connection are now limited to roughly 50 Mbit/s, which is above what a phone's uplink provides.

### Memory

The olcRTC engine bundled into the phone apps no longer carries two components no phone uses; on the tunnel side that is about ten megabytes less resident memory at rest and at a speed test's peak, and a much smaller download. On iOS the app also lets go of its whole screen while it is in the background and asks the runtime to collect it, so that a phone under memory pressure is not made worse by an app nobody is looking at. When you return, the app comes back on its home screen.

### Connections that were never closed

A connection whose remote side stopped answering used to be kept for as long as the session lasted; over hours these added up. They are now closed once they have been silent for a minute.

### Diagnostics

An exported log now keeps the trace of a tunnel run that was killed by the system, through any number of restarts after it, and adds a once-a-minute breakdown of what the tunnel process is busy with. Repeated network status lines are folded into a count so long sessions keep their full record.

### Relay resilience

A session closed by the server can no longer keep the app waiting on it for minutes: only real traffic on the app's own connections now counts as the remote still being there, and the wait for an answer is bounded to about a minute. Handshake and control messages go out as one unit, so a single dropped or reordered relay message cannot split them.
