# Reply to App Review — 1.0.435, Guidelines 3.1.1 and 2.3.10

Submission `86aa90d2-2fdd-4b7e-a56f-2ae290f6b07b`, reviewed 2026-09-19 on an iPad Air
11-inch (M3), rejected 2026-09-18 22:16. Fourth payments round; see
`app-review-3.1.1-reply.md`, `app-review-3.1.1-round3-reply.md`,
`app-review-2.1b-business-model-reply.md` for the earlier ones.

## What the reviewer wrote

**3.1.1** — "The app accesses digital content purchased outside the app, such as using
the VPN, but that content isn't available to purchase using In-App Purchase." One
screenshot attached.

**2.3.10** — "Revise the app's What's New text to remove Android references."

## Where each came from

**2.3.10 is ours and is not arguable.** The What's New text pasted into App Store
Connect came from the GitHub release body, which `release.yml` builds from the commit
log plus `docs/release-notes/pending.md`. That body is written for every platform at
once: it lists Android commits and ends with "Latest desktop, Android, and iOS builds
from public GitHub Actions CI". The App Store text has to be written separately, for
iOS only. Text to paste is below.

**3.1.1: what we know and what we do not.** The listing's screenshots are **not** the
trail. The set live in App Store Connect is the Ghostlane one: the onboarding cards
("Carried over WebRTC", "Every room has seats", "You bring the servers - No account,
nothing to buy, nothing collected"), the server-list settings screen, and the home
screen with the "How the VPN connection works" sheet, which says in the app's own words
that nothing is collected, that there is no account, and that the server belongs to
whoever gave the list and "Ghostlane does not run it and does not sell one". The old
ProofKit frames in `docs/screenshots/` (the 2026-09-08 set), two of which show an orange
`PLAN · RESETS IN 2913D … 4.3/5.0 GB` bar above a list named `@Xrayvlesspayment…`, are
**not** what is published.

So the reviewer's attached screenshot was taken during review, and we have not seen it.
Until we do, the candidates are, in order:
1. **The What's New text** we pasted, which contained "the client and the ProofKit
   marketplace are two different things". It was written to separate the two; to a
   reviewer scanning for a payment trail it introduces one. It also carried the Android
   lines that earned 2.3.10.
2. **The listing URLs**, which still lead to the pay-per-GB site: Marketing
   `proofkit.org`, Support `/help`, Privacy `/privacy`. This has been the reviewer's
   case in every round.
3. **A usage row inside the app**, if the reviewer imported a list that reports one.
   That row is the `subscription-userinfo` field a server list returns about itself
   (`LocationsDatasource.kt:1466`); ProofKit's own lists send no such header, so the
   review list cannot produce it.

**We have the reviewer's screenshot now (iPad, 19:15 on 18 September).** It shows the
room board of 1.0.435: the header, `NOT CONNECTED / Telemost · VP8`, a server list
group headed **"Encrypted list"**, five olcRTC rooms (US, IT, KR, SE, FR) each with its
free seats out of eight, and the button to take a seat. There is no price, no balance,
no quota, no account and no purchase anywhere on it.

Two things follow:
- Nothing on that screen can be fixed, because nothing on it claims a purchase. The
  citation rests on the app showing a catalogue of country exits at all, plus the
  listing's link to the pay-per-GB site.
- **"Encrypted list" is the app's own label** for a list whose import link hides the
  provider's host (`LocationSelection.kt:690`, `pkSubscriptionIsSecret`). So the tester
  imported an encrypted link. A hidden source next to a catalogue of countries invites
  exactly the reading we got. Give review a plain, readable test list instead.

## Changed on our side

- **The row is now labelled `TRAFFIC`, not `PLAN`** (`LocationSelection.kt`, `planLabel`).
  It is the provider's usage figure, and "plan" reads as something bought.
- The What's New text for the App Store is written separately from the release body
  (below), for iOS only.

## To paste into App Store Connect

### What's New (1.0.435, iOS only, no other platforms, no marketplace)

```
The app is now Ghostlane. Same app, same code, a new name and a new icon.

Your server lists, settings and the connection you are on carry over untouched. The
VPN profile in Settings is renamed with the app.
```

If 1.0.436 is submitted instead of 1.0.435, add:

```
Connecting through a room now loads its configuration the way a browser does, with
retries, so it works on more networks.

Joining a room over mobile data now waits as long as a slow carrier needs, instead of
giving up after five seconds.
```

### Reply to App Review (one message, both items)

```
Thank you for the review. Both items are addressed below.

Guideline 2.3.10. The What's New text has been rewritten and no longer refers to any
other platform. Our release notes are generated for every platform at once by our
open-source build process, and that text was pasted by mistake; the App Store text is
now written separately, for this app only.

Guideline 3.1.1. Three facts we would like on record.

1. The screen in the attached screenshot is the app's room board. It shows a server
list the tester imported, the rooms that list contains with how many of each room's
eight seats are free, and a button to take one. There is no price, no balance, no
quota, no account and no purchase anywhere on it, because the app has none of those
things. "Encrypted list" is not a product: it is the name the app gives a list whose
import link hides the provider's host, and it is what the tester imported.

2. The app has no account, no sign-in, no entitlement and no purchase path. It reads a
plain list of server addresses and credentials in the standard VLESS, Hysteria2 and
XHTTP link formats that every client of these protocols reads, and connects to what the
list names. It cannot tell whether a list was paid for, given away or typed by hand,
and it behaves identically in each case. There is nothing in it for an In-App Purchase
to unlock.

3. Our reading of the guidelines is 3.1.3(f): a free, stand-alone app. Some of our
users also use a paid service on a website; it is bought and managed entirely there,
the app never opens, mentions or links it, and there is no call to action for any
purchase inside the app. The app is equally a client for any other provider's servers,
and it is the only client on the App Store for olcRTC, an open-source transport that
carries traffic inside a WebRTC media session.

If review holds that 3.1.3(b) applies, we would be grateful to know which item in the
app is understood to be unlocked, since an In-App Purchase has to unlock something. We
are glad to provide a screen recording of import, connect and status, or any other
material that would help.
```

## Checklist before resubmitting

1. **What's New**: paste the text above. Nothing about Android, desktop, Windows,
   macOS, Google Play, the marketplace, or prices.
2. **Screenshots**: nothing to do. The published set is already the Ghostlane one and
   shows no usage bar; two of its frames state that there is no account and nothing to
   buy. Keep `docs/screenshots/` in mind only as a reminder that the old ProofKit set is
   stale.
3. **App Review Information**: replace the encrypted import link with a plain, readable
   list URL, and put this first: "This is a client for standard VPN
   protocols. The server list below is a free test configuration issued for review. The
   app has no account, no purchase path and no In-App Purchase."
4. **Listing URLs** (do this after the submission closes, not during review): Marketing
   and Support URLs should lead to a page about the client, with no prices and no
   purchase. See the table in `app-review-3.1.1-round3-reply.md`.
5. Reply, then resubmit the same build, or submit the newer one with the Traffic label.

## If it comes back again

The thread has answered 3.1.3(b) three times in words. The next step is the App Review
Board, with the same three facts, the open-source links, and the recording. The two
structural exits remain what they were: publish the client under its own name with its
own URLs and let the website be one provider among others, which the rename has now
started, or sell gigabyte packs through StoreKit for 3.1.3(b) parity.

## The App Review Information note, tightened

What we send today opens with two sentences that say the same thing twice, and its
first step hands review an `olcrtc://crypt1/...` link. That link is why their screenshot
says "Encrypted list": the app labels a list that way when the import link hides the
provider's host. A hidden source above a catalogue of countries is the impression we
are trying not to give.

Two changes: drop the duplicate opening, and give the plain form of the same room with
a label, `olcrtc://PROVIDER?TRANSPORT@ROOM#KEY$LABEL` (see `internal/link/link.go` in
the engine). The app then shows the label and the host instead of "Encrypted list".

Text to paste:

```
This is a free, stand-alone client for standard VPN protocols. The room link and the
server list below are free test configurations issued for review; nothing was bought to
create them, and the app has no account, no sign-in, no purchase path and no In-App
Purchase.

This version renames the app from "ProofKit VPN" to "Ghostlane": same bundle, same
functionality, same developer. It is an independent open-source client
(github.com/romanpodpriatov/ghostlane).

Ghostlane's distinguishing feature is olcRTC: a transport that carries the device's
traffic inside a WebRTC media session, an ordinary video call to a public meeting
service, so that on networks which drop every VPN protocol by signature, what remains on
the wire is a call the network already permits. We maintain the transport engine and
publish it at github.com/romanpodpriatov/olcrtc. This app is its only implementation on
iOS.

TO SEE THAT PART (two minutes)

1. Tap + in the top right, choose "Paste link or URI", and paste this olcRTC room link:

<PLAIN olcrtc:// LINK WITH A LABEL>

   One entry appears, an olcRTC room. Its occupancy bar is live: it shows how many of
   the room's seats are free and moves as they are taken. Select it and tap START. The
   app first shows its own disclosure ("How the VPN connection works"), then iOS asks to
   add the VPN configuration.

2. The standard protocols (Reality, Hysteria2, XHTTP), which the app supports so that a
   user does not need a second app when the ordinary transports stop working: paste this
   server list the same way, then pick any entry.

<TEST LIST URL>

Traffic is routed through Apple's NEPacketTunnelProvider. The app collects no data. The
camera is used only to scan a server-list QR code, and only when the user taps that
button.
```

If the plain link cannot be produced in time, keep the encrypted one and add this line
under step 1: *"The app labels this room 'Encrypted list' because the link hides the
provider's host. It is a label, not a product, and nothing was bought to create it."*
