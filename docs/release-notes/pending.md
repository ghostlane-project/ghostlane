This release changes the iPhone app only. Android, Windows, macOS and Linux are built from the code of 1.0.447 and behave as it does.

### iPhone: the kill switch stays up while the app itself restarts the tunnel

When the app restarted the tunnel itself, to change the server or in its own reconnect, it first switched off the rule that has iOS hold traffic, and wrote it again with the next start. In between nothing told iOS to hold anything, so the phone could send traffic directly; after a reconnect that failed, for the whole pause before the next attempt. A restart now leaves that rule on, and iOS holds traffic until the next tunnel is up. When a restart fails, the app no longer lowers the switch either: iOS and the app keep trying, the screen says so, and Cancel gives the phone its own network back. A first connect that fails ends in its error as before, with the network as it was.

### iPhone: the kill switch no longer fights the app's own reconnect

With the kill switch on, a tunnel that goes down is brought back by iOS itself. The app's own reconnect did not know that and went to work as well: it stopped the tunnel iOS had just brought up and started another, which cost about ten more seconds without a connection. And if you had left the app meanwhile, it could come back to a tunnel that was working and say it had died ("died at stage 'ready'"), then stop it and connect again. With the switch on the app now waits for iOS to bring the tunnel back and takes that one; it restarts the tunnel itself only if iOS has not managed in 45 seconds. The false error is gone with it.
