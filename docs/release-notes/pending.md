### Computers: Bypass in olcRTC rooms on Linux, and your network's own DNS on macOS and Windows

On Linux, Bypass Russia, Iran and China, and your own "always direct" rules, now work in olcRTC rooms: the country's sites and your local network connect directly, and everything else goes through the room. With other kinds of server, Linux still sends everything through the tunnel, and the routing screen says so. On macOS and Windows, olcRTC now looks names up on your network's own DNS servers first and on a public one after them, so it no longer waits on networks that block public DNS. In the Windows tunnel, routing still carries everything; the screen now says to switch to proxy mode for it.

### olcRTC: VK Calls rooms

Ghostlane now connects to olcRTC rooms on VK Calls, the fifth meeting service after Yandex Telemost, WB Stream, Sber SaluteJazz and Jitsi. Import a link that names `vkcalls`, or choose VK Calls as the service in a location's settings and paste the call's join link (`https://vk.ru/call/join/…`). VK Calls rooms run over the VP8 channel.

### olcRTC: a Chrome-like handshake to try (experimental)

Connection settings have a new switch, off by default. With it on, olcRTC rooms open their encrypted connection the way Chrome does, not the way their WebRTC library does. Try it if rooms fail to connect on your network; changing it reconnects an active tunnel, and room checks use the same handshake.
