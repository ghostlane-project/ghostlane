### WB Stream rooms

A WB Stream room failed on the phone right after signing in, with "engine not found", while the same room worked on desktop. The phone build had left out the piece that carries those rooms; it is back.

### Jitsi rooms that never answered

On a Jitsi room the phone sometimes reported that the server did not answer, although the server was up and a desktop client connected to the same room. When the server's side of the room comes up a few seconds after the phone's, the phone's first greeting is lost on the way; the phone now repeats it every four seconds until the server replies.
