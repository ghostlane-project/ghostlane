# Ghostlane privacy policy

Effective 2026-09-25. Applies to the Ghostlane app on iOS, Android, macOS, Windows
and Linux, published from this repository.

## What the app collects

Nothing. The app has no account, no sign-in, no analytics, no advertising
identifier and no crash-reporting service. It does not ask for your name, e-mail,
phone number or location, and it does not read your contacts, photos or files
beyond a server-list file you choose to import.

## What stays on your device

- The server lists you add, and the servers in them.
- Your settings: the selected server, routing mode, and the intervals at which
  lists are refreshed.
- The app's own log and the diagnostics export, which you can share if you choose
  to. Before export the log is scrubbed of addresses and credentials.

None of this leaves the device unless you export and send it yourself.

## Where the app connects

- **The server-list URL you added**, to download and refresh that list.
- **The VPN servers in that list**, to carry your traffic. What those servers do
  with it is governed by whoever operates them, not by this app.
- **A check that the tunnel works**, when a connection starts: one small HTTPS
  request to `1.1.1.1/cdn-cgi/trace` (or `cloudflare.com/cdn-cgi/trace`) through
  the tunnel. Its answer is the exit's address and country, which the app shows.
- **A latency check** while connected: one small HTTPS request to
  `www.gstatic.com/generate_204` through the tunnel every 30 seconds while the
  home screen is in the foreground, so the app can show the channel's response
  time.
- **Smart connect**, on Android and computers, before connecting to a server
  published over more than one kind of connection (on by default; off in the
  server-list settings). Through each kind it tries: one request to
  `www.gstatic.com/generate_204` and a 64 KB download from `speed.cloudflare.com`.
  Outside any tunnel: one request each to `www.gstatic.com/generate_204` and
  `ya.ru`, to recognise a network that lets only domestic sites through.
- **Room checks** for olcRTC rooms. The app asks `proofkit.org` how full each
  room is, naming the room by a one-way handle derived from its key (the first
  16 hex digits of its SHA-256), never the key itself. When the app measures a
  room on Android or a computer, it sends one request to
  `www.google.com/generate_204` through that room.
- **A partner link resolver**, only when you open a partner link that has to be
  turned into a server-list URL. That request carries the link.
- **GitHub**, to check for updates on the desktop and Android builds. The
  request fetches the release list.

None of these requests carries anything that identifies you or your device.

The app never opens, mentions or links to any website of its own, and it has no
purchase of any kind.

## Camera

Used only to scan a server-list QR code, only while you are on the scanner
screen. No photo or video is recorded, stored or uploaded.

## Children

The app is not directed at children and collects no data from anyone.

## Changes

Changes to this policy are made in this file, in this repository, where the
history is public.

## Contact

Open an issue in this repository.
