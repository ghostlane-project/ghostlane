### Bypass Russia on olcRTC rooms

Since 1.0.428 the routing choice did nothing on an olcRTC room on iOS: everything went through the room, Russian sites included. The rules now live in the olcRTC engine itself. With Bypass Russia on, Russian sites, .ru names and your local network go straight out from the phone and are resolved by your network's own resolver, while everything else rides the room — the same lists the app uses on xhttp, and no second engine in front of olcRTC.
