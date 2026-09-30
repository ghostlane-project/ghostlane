# ghostlane — Ghostlane for Linux servers

One binary, one service. It joins an olcRTC room (a tunnel inside a video call:
Telemost, WB Stream, SaluteJazz, VK Calls) with failover between carriers and
routes the machine through it.

    ghostlane add <list-url>            # your subscription (or one olcrtc:// line)
    ghostlane list                      # every line, with its country and carrier
    ghostlane connect DE --proxy        # SOCKS5 + HTTP on 127.0.0.1:1080, no privileges
    ghostlane connect DE --tun          # the whole machine through the tunnel
    ghostlane status                    # state, line, proxy lines to paste
    ghostlane disconnect

Modes. `--proxy` serves a local proxy only; `status` prints the `http_proxy` /
`ALL_PROXY` lines. `--tun` creates `ghostlane0` and routes everything through
it except private ranges and the machine's own addresses: SSH and any service
on the public IP keep working (a policy rule ahead of sing-box's keeps their
replies on the normal route). IPv6 is refused rather than leaked. Stopping the
service removes the tun and its rules: traffic flows directly again.

Service. `systemctl status ghostlane`, logs in `journalctl -u ghostlane`. The
daemon runs as user `ghostlane` with CAP_NET_ADMIN; its files live under
`/var/lib/ghostlane` (root-only; the config holds your list URL). The
selection survives a reboot: the service reconnects at boot.

Carriers. `connect DE` tries the country's rooms in the list's order
(Telemost, WB Stream, SaluteJazz, VK Calls); a room that does not answer within
a minute, or three failed liveness probes in a row, move it to the next; the
last working room is tried first next time.

Not yet: VLESS/Hysteria2/XHTTP lines (the next version), encrypted (crypt1)
lists, a kill switch, IPv6 through the tunnel.

Licence: GPL-3.0-or-later (the binary links sing-box). Source and issues:
https://github.com/ghostlane-project/ghostlane
