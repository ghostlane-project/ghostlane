# App Store listing — copy, answers, and the things review will ask for

Everything below is ready to paste. English only, which is deliberate: the app
has no translations, and a localised listing over an untranslated app reads as
a bait.

Field lengths are Apple's and are enforced; the counts in brackets are what the
text below actually uses.

---

## Name and identity

**App Name** (30 max) — [9]

```
Ghostlane
```

> Renamed from ProofKit on 2026-09-16 (see `app-review-3.1.1-round3-reply.md`):
> the store app is the open-source client, not the marketplace. Checked in the
> US and RU storefronts: no app carries the name. The bundle's display name
> (`Info.plist`), the Android label and the desktop package name all say
> `Ghostlane` so the home screen and the listing agree.

**Subtitle** (30 max) — [28]

```
A tunnel inside a video call
```

> It used to read `Reality, Hysteria2 and XHTTP`, which is the protocol list every
> client in this category prints, and it is part of what cost a **4.3(a) spam**
> rejection on 2026-08-12. The subtitle is indexed for search and is the second
> thing a reviewer reads; spend it on what only this app does. See
> `docs/app-review-4.3-reply.md`.

**Category** — Primary: *Utilities*. Secondary: *Productivity*.

> Not "Social Networking" and not "Travel", both of which VPN clients sometimes
> pick and both of which invite a category-mismatch rejection.

---

## Promotional text (170 max) — [164]

Editable without a new build, so this is the line to change when something
ships.

```
olcRTC carries your traffic inside a WebRTC video call, so a network that blocks
every VPN protocol sees a call it already allows. Reality, Hysteria2 and XHTTP too.
```

---

## Description (4000 max)

```
Ghostlane carries your traffic inside a video call.

Most tunnels are recognisable. On a network that inspects what passes through it
and drops anything shaped like a VPN, they stop working — not because the
encryption failed, but because the shape of the connection gave it away.

olcRTC is a different answer. It opens a WebRTC media session to a public
meeting service — the same kind of call the network already carries all day —
and moves your traffic inside it. What stays on the wire is a video call.

ROOMS, AND HOW FULL THEY ARE

An olcRTC relay holds a fixed number of slots, and a full room cannot take you.
So the app asks each room how full it is and shows that in the list, before you
pick one. It is the sort of thing you only need when the thing you are
connecting to has rooms.

IT ALSO SPEAKS THE ORDINARY PROTOCOLS

A call-shaped tunnel costs bandwidth, and it is not needed until it is. The same
app connects over the standard transports too, and you move between them as the
network around you changes:

• VLESS with Reality
• VLESS over TLS, including through a CDN
• Hysteria2, with Salamander obfuscation
• XHTTP

One app, because the moment you need the fallback is the worst possible moment
to be installing another one.

BRINGING YOUR OWN SERVERS

Add a server list by pasting its link, scanning its QR code, or importing a
file. The app groups servers by where they came from, filters them by protocol,
remembers which exit you last used, and refreshes the list on a schedule you
choose.

Connecting routes the whole device through the exit you picked, using Apple's
own packet tunnel. The status screen shows how long the session has been up and
how much has gone through it.

MEASURING

Latency is measured where it can honestly be measured: through the tunnel for
the connection you are on, and by ICMP echo for a server you are not connected
to. Where neither is possible the app says so instead of showing a number it
guessed.

PRIVACY

Nothing is collected. No account, no analytics, no advertising identifier, no
crash reporting service. Your server lists and the app's own log stay on the
device and are never sent anywhere.

Besides the server lists you add and the servers in them, the app makes a few
small checks that carry nothing about you: whether the tunnel works and how fast
it answers, and how full each olcRTC room is. The privacy policy names every
address.

REQUIREMENTS

A server configuration from a VPN provider: a server-list URL, a QR code, or a
pasted link. The app does not sell one and does not include one. Any provider
that speaks the protocols above will work.

OPEN SOURCE

github.com/ghostlane-project/ghostlane
```

> **"The app does not sell one", not "ProofKit does not sell one".** ProofKit the
> company does sell VPN bandwidth, per gigabyte, on the website the Marketing URL points
> at, and a reviewer who has just read that page reads the old sentence as false. The
> 2.1(b) business-model questionnaire of 2026-09-15 came from exactly that page — see
> `docs/app-review-2.1b-business-model-reply.md`.

> **Do not put a purchase pointer back into this text.** The sentence that used to
> close REQUIREMENTS — "the app can open proofkit.org, where you can get a
> subscription" — is what kept Guideline 3.1.1 alive after the control itself was
> removed from the app on 2026-08-04. We told review the pointer was gone while the
> listing still advertised it, so from their side nothing had changed. Metadata is
> reviewed alongside the binary; a call to action for a purchase counts wherever it
> appears.

---

## Keywords (100 max, comma-separated, no spaces after commas) — [95]

```
olcrtc,webrtc,videocall,jitsi,telemost,censorship,dpi,firewall,obfuscation,tunnel,relay,traffic
```

> Do not repeat the app name or subtitle — Apple already indexes those, and the
> characters are scarce.

> **`sing-box` and `xray` were in this field**, and they are the names of the two
> engines this app shares with the clients it was mistaken for. We wrote the
> similarity into the metadata ourselves, next to `vless`, `proxy`, `socks` and
> `subscription` — the rest of the cluster's signature. All of them are gone.
>
> This costs real discoverability: Apple indexes the name, subtitle and this field
> for search, and **not** the description, so dropping `reality` and `hysteria2`
> means the app will not surface for them. That is the trade, and it is worth it
> while a 4.3(a) rejection is open. Revisit after approval — carefully, one term at
> a time, and never `sing-box` or `xray` again.

---

## What's New (first release)

```
First release.
```

---

## URLs

| Field | Value |
|---|---|
| Support URL | `https://github.com/ghostlane-project/ghostlane/blob/main/docs/support.md` — **must resolve and must answer questions**; a 404 here is a rejection |
| Marketing URL | `https://github.com/ghostlane-project/ghostlane` |
| Privacy Policy URL | `https://github.com/ghostlane-project/ghostlane/blob/main/docs/privacy.md` — required, and required to match the App Privacy answers below |

> Changed 2026-09-16 from the three proofkit.org pages. Every one of them opened on
> "Pay-per-GB … Marketplace", "set per-GB pricing" or "bill pay-per-GB usage", and the
> 2.1(b) and 3.1.1 rounds of September were built from those pages, not from the
> binary. The client's own pages describe the client: what it does, how to import,
> what it collects (nothing). The marketplace site may link to the app; the app's
> listing never links to the marketplace.

---

## App Privacy answers

Every question, answered "no data collected", because that is what the app
does. This section is the one Apple checks against behaviour, so it must stay
true if analytics or crash reporting is ever added.

- **Do you or your third-party partners collect data from this app?** → **No**

That single answer ends the questionnaire. It is consistent with
`PrivacyInfo.xcprivacy`, which declares no collected data types, no tracking
domains, and one required-reason API (`NSUserDefaults`, `CA92.1`).

> If a crash reporter or analytics SDK is ever added, both this answer and the
> privacy manifest have to change in the same commit as the SDK. A mismatch
> between them is a rejection that arrives after the build is processed.

---

## Export compliance

Answered in `Info.plist` as `ITSAppUsesNonExemptEncryption = false`, so App
Store Connect stops asking per upload.

**Why false, and why not for the reason people usually give.** It is not exempt
because the algorithms are standard. Almost every app uses standard algorithms
and is caught by ECCN 5D992 regardless; "we have no proprietary crypto" answers
a *different* question in the questionnaire, and the answer there is indeed
**No**, but it settles nothing here.

It is exempt because the source is public. Under **EAR §742.15(b)**, publicly
available encryption object code whose corresponding source is publicly
available **and has been notified to BIS and the NSA** is *not subject to the
EAR*. No registration number, no annual self-classification report, nothing
filed each January. Everything this app encrypts with is in a public
repository — olcbox itself, the olcrtc fork, sing-box, Xray.

**The notification is a condition, not a formality.** Until the email below has
been sent, the `false` in `Info.plist` is a false declaration on a customs
question. Send it once, before the first upload, and keep the sent copy.

### The email — send once

To: `crypt@bis.doc.gov`, `enc@nsa.gov`
Subject: `Notification of publicly available encryption source code`

```
This is a notification under Section 742.15(b) of the Export Administration
Regulations of publicly available encryption source code.

Product:            Ghostlane (olcbox) — VPN client for iOS, Android, macOS,
                    Windows and Linux
Source code URL:    https://github.com/ghostlane-project/ghostlane

The complete corresponding source code is available at the URL above without
charge and without restriction on access. Cryptographic functionality is
provided by publicly available components:

  https://github.com/ghostlane-project/olcrtc
  https://github.com/SagerNet/sing-box
  https://github.com/XTLS/Xray-core

The algorithms used are published standards: AES, ChaCha20-Poly1305 and TLS.
No cryptographic algorithm developed by us is included.

Submitter:          Globvent inc
Contact:            <name, email, telephone>
```

> Reply-to and contact details have to be a real person who can answer a
> follow-up. There is no acknowledgement to wait for — the notification is
> effective on sending — but keep the sent message, because the only proof that
> it went is your own copy of it.

### If this ever stops being true

A private fork of a core, a vendored binary nobody can read, or crypto written
here and not published: any of those and the app is no longer publicly
available encryption. The answer goes back to `true`, and the route becomes a
one-time **BIS Encryption Registration Number** plus an **annual
self-classification report for ECCN 5D992.c**, emailed each January.

### Questionnaire answers, if App Store Connect still asks

- Uses encryption → **Yes**
- Qualifies for an exemption → **Yes**, publicly available under §742.15(b)
- *"Proprietary encryption algorithms?"* → **No**. The question is about
  algorithms, not protocols. Ours are AES, ChaCha20-Poly1305 and TLS; the
  protocols around them are bespoke, which is a different thing and not what is
  being asked.
- CCATS → none, and none is needed on this route. Do not invent a number.

> **What other clients answer is not knowable and would not help.** Export
> answers are not published, and a closed-source client cannot use this route at
> all — it has to register. Copying its answer would be copying the wrong one.

---

## App Review notes — the part that actually decides this

Paste into "Notes" in App Store Connect:

```
This is a free client for standard VPN protocols. The room link and the server
list below are a free test configuration issued for review; nothing was bought
to create them. The app has no account, no sign-in, no purchase path and no
In-App Purchase. This version renames the app from "ProofKit VPN" to
"Ghostlane": same bundle, same functionality, same developer. The name now
reflects what it is, an independent open-source client
(github.com/ghostlane-project/ghostlane).

Ghostlane's distinguishing feature is olcRTC: a transport that carries the
device's traffic inside a WebRTC media session, an ordinary video call to a
public meeting service, so that on networks which drop every VPN protocol by
signature, what remains on the wire is a call the network already permits. We
maintain the transport engine and publish it at github.com/ghostlane-project/olcrtc.
This app is its only implementation on iOS.

TO SEE THAT PART (two minutes)

1. Tap + in the top right, choose "Paste link or URI", and paste this olcRTC
   room link:

<PASTE A LIVE olcrtc://crypt1/… ROOM LINK HERE>

   One entry appears, an olcRTC room. Its occupancy bar is live: it shows how
   many slots the relay room has left and moves as slots are taken. Select it
   and tap START. The app first shows its own disclosure ("How the VPN
   connection works"), then iOS asks to add the VPN configuration.

2. The standard protocols (Reality, Hysteria2, XHTTP), which the app supports
   so that a user does not need a second app when the ordinary transports stop
   working: paste this server list the same way, then pick any entry.

<PASTE A LIVE SERVER-LIST URL HERE>

Traffic is routed through Apple's NEPacketTunnelProvider. The app collects no
data. The camera is used only to scan a server-list QR code, and only when the
user taps that button.
```

> **This is the single most likely cause of a rejection.** A reviewer who
> cannot connect cannot review, and "we could not test the app's core
> functionality" is 2.1. The link must be live on the day of review and for
> some days after — a server list that expires mid-review fails it.

> **The room link comes first, as its own step**, because the marketplace list
> carries no olcRTC entry at all (checked 2026-09-17: 88 entries, 58 VLESS, 29
> Hysteria2, 1 SOCKS). A reviewer told "the first entry is an olcRTC location" and
> handed that list lands on a Reality server, the generic half of the app, which is
> the half that got it rejected. Check the room has free slots on the day.

---

## Age rating

All questions "None". The result is 4+.

> There is an argument for 17+ on "Unrestricted Web Access", and some VPN apps
> carry it. This app has no browser and no content of its own — it routes the
> system's traffic — so 4+ is defensible. If review disagrees, changing the
> rating does not need a new build.

---

## Screenshots

**One set, iPhone only.** The app now targets `TARGETED_DEVICE_FAMILY = 1`; it
used to claim iPad as well, which would have meant a second set of screenshots
and a review on a 13" screen of an interface built around a 200pt dial and a
phone-width list. Claiming a device the layout was not designed for is how an
app gets rejected for something nobody intended to ship.

Required size: **6.9" iPhone — 1320 × 2868**. Apple derives every smaller size
itself.

### What the Simulator cannot make

**Network Extension does not exist in the Simulator.** Not "does not work
well" — the framework is absent, `saveToPreferences` fails, and the app now
says so plainly instead of reporting "no VPN configuration", which reads as
something missing that could be supplied.

So anything showing a live tunnel — `01-olcrtc-connected`, and the system
permission prompt if it is ever shot — needs a real device. The rest, including
`02-rooms-occupancy`, is Simulator work: occupancy is fetched over the network
and does not need the tunnel up to be shown.

The device has to be the right size: App Store Connect accepts **1320 × 2868**
(iPhone 16/17 Pro Max) or **1290 × 2796** (iPhone 14/15 Pro Max, or a Plus) for
this slot. A 6.1" phone produces 1179 × 2556, which is not accepted and cannot
honestly be scaled into one that is.

If no Pro Max is to hand, the options are borrowing one, or shipping the four
the Simulator can produce — Apple requires a minimum of one screenshot, not
six. Four honest screenshots beat six with two upscaled ones.

### Capturing

From a simulator, for everything except the connected screen:

```bash
xcrun simctl list devices available | grep "Pro Max"     # pick the 6.9" one
xcrun simctl boot "iPhone 17 Pro Max"
open -a Simulator
# …drive the app to the state you want, then, per screen:
xcrun simctl io booted screenshot screenshots/02-rooms-occupancy.png
```

Then, before uploading anything:

```bash
bash scripts/check-screenshots.sh screenshots/
```

It checks the three things App Store Connect refuses a set for and only tells
you about afterwards: a size off by a pixel, an alpha channel an editor left
behind, and a file that is a JPEG wearing a `.png`. It validates and never
resizes — a screenshot scaled to fit is a screenshot of the wrong thing.

> It needs `sips` and so **runs on macOS only**. On a Linux box the same three
> checks read out of the PNG header directly — magic bytes, the `IHDR` width and
> height, and colour type 2 for truecolour without alpha.

### The set, strongest first

These are the files in `docs/screenshots/`. Keep them in step with the app: the
repository is linked from the listing as OPEN SOURCE, so a reviewer who follows
that link should meet the same app.

**Order matters more than it looks.** The set uploaded on 2026-08-08 opened on
`01-empty` — a blank screen reading "Import a server list to start". That is the
first thing review saw of the app, and it is indistinguishable from every other
empty client in the category; it is part of what earned 4.3(a). Slot 1 and slot 2
are the only two most people ever look at, so they have to carry the thing no
other app has.

The set as shot on 2026-08-14, all four at 1320 × 2868, on a real device:

1. `01-olcrtc-connected` — **connected over olcRTC**: `RELAY ACTIVE · OLCRTC`,
   session timer, traffic counters, "Traffic exits via US", and the protocol chips
   with `OLCRTC 16` selected. The one frame that carries the whole argument.
2. `02-rooms-occupancy` — **the rooms, and how full they are**: `US · olcRTC`,
   `Telemost · VP8`, `1 / 8` with the bar part-filled. No other client has this,
   because no other client connects to something with rooms.
3. `03-settings` — **settings**, so it is clear the app is configurable rather
   than a black box.
4. `04-addconnection` — **adding a connection**: scan, paste, or import.

Apple requires a minimum of one screenshot, so four is a set. Two files that were
in the 2026-08-08 upload are deliberately gone:

- `01-empty` — an empty state is not a feature and was the worst possible first
  frame; opening on it is part of what earned 4.3(a).
- `02-vpnpopup` — it photographed `VpnDisclosureScreen`, not the system prompt,
  and that screen said "**Android** will ask you" on an iPhone and used the word
  "subscription" five times. Both fixed in the source on 2026-08-14. If it is
  re-shot, read it first: it is the one screen whose text nobody checked against
  either the 3.1.1 rename or the platform it renders on.

Nothing in the set may show a purchase, a price, or a way to get one — that is
the reading that cost two rejections under 3.1.1. See
`docs/app-review-3.1.1-reply.md`.

### Two things to get right before pressing the shutter

- **Build with the admin gate on.** A screenshot showing the per-location
  configurator or "create custom location" invites questions about what else is
  hidden, and shows an operator's addresses to anyone who zooms in.
- **Use the demo server list**, the same one that goes in the review notes. A
  real one puts a real provider's server names and quota in a public listing.

No device frames and no marketing text over the top. Apple allows both; both
date badly, and neither survives the next redesign.
