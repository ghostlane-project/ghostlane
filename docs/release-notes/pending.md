For Android this release is the code of 1.0.447, and it behaves as that one does.

### iPhone: the kill switch stays up while the app itself restarts the tunnel

When the app restarted the tunnel itself, to change the server or in its own reconnect, it first switched off the rule that has iOS hold traffic, and wrote it again with the next start. In between nothing told iOS to hold anything, so the phone could send traffic directly; after a reconnect that failed, for the whole pause before the next attempt. A restart now leaves that rule on, so that iOS goes on holding traffic until the next tunnel is up. When a restart fails, the app no longer lowers the switch either: iOS and the app keep trying, the screen says so, and Cancel gives the phone its own network back. A first connect that fails ends in its error as before, and the phone has its own network back.

Two things still lower the switch without being asked. A restart the app cannot even ask for, a server whose link it cannot read for one, ends in an error with the VPN stopped. And with "lowest latency" selection on, the app moves to another exit after a reconnect that failed by stopping the VPN and starting it again, and it measures the exits with the VPN stopped.

### iPhone: the kill switch no longer fights the app's own reconnect

With the kill switch on, a tunnel that goes down is brought back by iOS itself. The app's own reconnect did not know that and went to work as well: it stopped the tunnel iOS had just brought up and started another, which cost about ten more seconds without a connection. And if you had left the app meanwhile, it could come back to a tunnel that was working and say it had died ("died at stage 'ready'"), then stop it and connect again. With the switch on the app now waits for iOS to bring the tunnel back and takes that one; it restarts the tunnel itself only if iOS has not managed in 45 seconds. The false error is gone with it.

### Desktop: the tray icon shows whether the VPN is up

The icon in the Windows tray, the macOS menu bar and the Linux panel looked the same whether the VPN was up or not. On Windows the mark now turns green while it is up. In the macOS menu bar, where the system paints every icon in the bar's own colour, the mark is at full strength while the VPN is up and dimmed while it is not. On Linux the tile gets a green frame. The tooltip says it in words as well: connected, connecting, not connected.
