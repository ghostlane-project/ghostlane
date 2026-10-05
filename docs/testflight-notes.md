# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

This build is about what happens when the tunnel is not there: IPv6 that went
around it, and a kill switch for when it stops.

WHAT CHANGED

• IPv6 is inside the tunnel now. On a network that hands out IPv6, a
  connection to an IPv6 address could leave directly, at the phone's own
  address, with the VPN shown as connected. The tunnel now takes IPv6 too.
  On a Reality or Hysteria2 server it is carried to the exit; in an olcRTC
  room and on an XHTTP server it is dropped, and apps fall back to IPv4
  through the tunnel.
• A kill switch: Settings → Connection, off by default. With it on, when
  the VPN is not up iOS drops traffic instead of sending it directly, and
  brings the VPN back by itself. The note under the switch says what it can
  cost: if the VPN cannot come back, the phone has no network until you turn
  the VPN off in the system's Settings.
• The switch stays up while the app itself restarts the tunnel: when you
  change the server, and in the app's own reconnect. In the build before
  this one it was lowered for that moment.
• Server links that use the HTTP transport now connect, and so do VLESS
  servers with ordinary TLS, a self-signed certificate, or no TLS.

WHAT TO TEST

1. IPv6. On a mobile network that hands out IPv6, connect and open
   test-ipv6.com, once in an olcRTC room and once on a Reality server. It
   must not show an IPv6 address of the phone's own. On the build before
   this one it did, in the room.
2. Pages in an olcRTC room must still start loading promptly: IPv6 is
   dropped there and apps fall back to IPv4.
3. If you have an IPv6-only network (some carriers, or a Mac sharing its
   connection with NAT64 on): the app must still connect.
4. Kill switch on, connect. Then make the tunnel go away without pressing
   Disconnect: leave a speed test running until iOS stops the VPN, or wait
   through a long idle. Traffic must stop, not continue outside the VPN,
   and the VPN must come back by itself.
5. Kill switch on, connected: turn the VPN off in the system's Settings.
   It must come back by itself. Back in the app it must say Connected, with
   no red message, and it must not reconnect once more. Then the same, but
   stay in Settings for a few minutes before you return to the app.
6. Kill switch on, press Disconnect in the app. It must stay disconnected,
   and the phone must have its ordinary network back.
7. Kill switch on: an olcRTC room and an XHTTP server must still carry
   traffic, and AirPlay or a printer on your Wi-Fi must still be reachable.
8. Kill switch on, an address-check page open (ifconfig.me), change the
   server in the app and keep reloading the page while it changes. It must
   fail to load or show a server's address, never your own. Tell us if
   your own address shows at any moment.
9. Kill switch on, connected: change to a server that fails to connect.
   An olcRTC room that is gone is one; a Reality or Hysteria2 server that
   is down still shows as connected, and is not this test. The app must
   stay on "reconnecting", the phone must have no network meanwhile, and
   Cancel must give its ordinary network back. Choosing a working server
   instead must connect.
10. Kill switch off: everything as before.
11. If the phone ends up with no network and the VPN will not come back:
    Settings → VPN → turn it off. Tell us what you were doing.
