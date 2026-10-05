### iPhone: the kill switch no longer fights the app's own reconnect

With the kill switch on, a tunnel that goes down is brought back by iOS itself. The app's own reconnect did not know that and went to work as well: it stopped the tunnel iOS had just brought up and started another, which cost about ten more seconds without a connection. And if you had left the app meanwhile, it could come back to a tunnel that was working and say it had died ("died at stage 'ready'"), then stop it and connect again. With the switch on the app now waits for iOS to bring the tunnel back and takes that one; it restarts the tunnel itself only if iOS has not managed in 45 seconds. The false error is gone with it.

### Desktop: the tray icon shows whether the VPN is up

The icon in the Windows tray, the macOS menu bar and the Linux panel looked the same whether the VPN was up or not. On Windows the mark now turns green while it is up. In the macOS menu bar, where the system paints every icon in the bar's own colour, the mark is at full strength while the VPN is up and dimmed while it is not. On Linux the tile gets a green frame. The tooltip says it in words as well: connected, connecting, not connected.
