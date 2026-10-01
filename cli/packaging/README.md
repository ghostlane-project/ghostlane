# ghostlane — Ghostlane for Linux servers

One binary, one service. It connects the lines of your subscription — an
olcRTC room (a tunnel inside a video call: Telemost, WB Stream, SaluteJazz, VK
Calls), VLESS Reality, Hysteria2 or XHTTP — with failover between them, and
routes the machine through the tunnel.

    ghostlane add <list-url>            # your subscription (plain or encrypted), or one olcrtc:// / vless:// / hy2:// line
    ghostlane list                      # every line, with its country and carrier
    ghostlane connect DE --proxy        # SOCKS5 + HTTP on 127.0.0.1:1080, no privileges
    ghostlane connect DE --tun          # the whole machine through the tunnel
    ghostlane connect DE --tun --kill-switch   # …and refuse traffic outside it while no line is up
    ghostlane status                    # state, line, proxy lines to paste
    ghostlane disconnect

Modes. `--proxy` serves a local proxy only; `status` prints the `http_proxy` /
`ALL_PROXY` lines. `--tun` creates `ghostlane0` and routes everything through
it except private ranges and the machine's own addresses: SSH and any service
on the public IP keep working (a policy rule ahead of sing-box's keeps their
replies on the normal route). IPv6 is refused rather than leaked. Stopping the
service or `disconnect` removes the tun and its rules: traffic flows directly
again — unless `--kill-switch` was given, which keeps the box closed while the
selection stands and no line is up (`disconnect` opens it).

Service. `systemctl status ghostlane`, logs in `journalctl -u ghostlane` (on
Alpine: `rc-service ghostlane status`, `/var/log/ghostlane.log`). The
daemon runs as user `ghostlane` with CAP_NET_ADMIN; its files live under
`/var/lib/ghostlane` (root-only; the config holds your list URL). The
selection survives a reboot: the service reconnects at boot.

Lines. `connect DE` tries the country's lines in the list's order (rooms:
Telemost, WB Stream, SaluteJazz, VK Calls; then servers); a line that does not
answer within a minute, or three failed liveness probes in a row, moves it to
the next; the last working line is tried first next time. `connect 6` names
one line by its number in `list`.

Not in this version: VLESS over grpc/ws, trojan, shadowsocks, vmess; IPv6
through the tunnel. Full guide: docs/cli.md in the repository.

Licence: GPL-3.0-or-later (the binary links sing-box). Source and issues:
https://github.com/ghostlane-project/ghostlane
