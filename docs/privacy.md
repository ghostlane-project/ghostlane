# Ghostlane privacy policy

Effective 2026-09-16. Applies to the Ghostlane app on iOS, Android, macOS, Windows
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
- **A latency check** while connected: one small HTTPS request to
  `www.gstatic.com/generate_204` through the tunnel every 30 seconds while the
  home screen is in the foreground, so the app can show the channel's response
  time. It carries no identifier.
- **A partner link resolver**, only when you open a partner link that has to be
  turned into a server-list URL. That request carries the link and nothing about
  you.
- **GitHub**, to check for updates on the desktop and Android builds. The
  request fetches the release list and sends no identifier.

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
