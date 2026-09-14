### One engine in the tunnel, on every path that needs one

olcRTC locations on iOS now run the same arrangement that let xhttp survive a speed test in 1.0.426: the small native tun2socks in front of the transport engine, and no second network engine in the 50 MB extension. Name lookups on that path travel inside the tunnel's reliable stream rather than the relay's packet lane, so they are not lost when the link is busy.

### Memory

On iOS the app now gives its screen the appearance callbacks it was owed when it lets go of it in the background, and the tunnel extension hands idle memory back to the system as soon as it passes 36 MB, before the system asks. The exported log names any view left under the window in full.
