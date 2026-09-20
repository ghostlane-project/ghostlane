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

**3.1.1 has a concrete trail this time, and it is in the listing, not the binary.**
Two of the five store screenshots (`docs/screenshots/03-connected.PNG` and
`05-serverlistnotconnected.PNG`, the 2026-09-08 set) show, at the top of the server
list, an orange usage bar reading `PLAN · RESETS IN 2913D … 4.3/5.0 GB`, directly under
a list named `@Xrayvlesspayment…`. A reviewer reading that screen sees a plan with a
quota and the word "payment", in an app with no In-App Purchase. Both screenshots also
still carry the old name, ProofKit, which the app no longer uses.

Three facts about that row, all verifiable:
- It is drawn from the `subscription-userinfo` header the server list itself returns
  (`LocationsDatasource.kt:1466`), which is the standard field every client of these
  protocols reads. The app displays what the list reports and nothing else.
- The list in the screenshot is a third party's public Telegram list, not ours.
- ProofKit's own lists send no `subscription-userinfo` at all, so for them this row
  never appears.

The What's New text also contained the sentence "the client and the ProofKit
marketplace are two different things". It was written to separate the two; to a
reviewer scanning for a payment trail it introduces one.

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

1. The app has no account, no sign-in, no balance, no entitlement and no purchase
path of any kind. It imports a plain list of server addresses and credentials in the
standard VLESS, Hysteria2 and XHTTP link formats that every client of these protocols
reads, and it connects to what the list names. It cannot tell whether a list was paid
for, given away or typed by hand, and it behaves identically in each case. There is
nothing in the app for an In-App Purchase to unlock.

2. About the usage row in the screenshot. That line is not ours and not a purchase
record: it is the "subscription-userinfo" field that a server list itself returns in
its HTTP response, a standard field of these protocols that every client of them
displays. The app shows what a list reports about itself and nothing more. The list
shown in our screenshots is a third party's public list, which is why it reports a
figure at all. In the build we are submitting, that row is labelled "Traffic" rather
than "Plan", and we are replacing the store screenshots so the point is not implied
again.

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
2. **Screenshots**: replace at least the two frames that show the usage bar. New frames
   must show the Ghostlane name and must not show a usage bar, a provider list whose
   name contains "payment", or any figure that can be read as an allowance. The empty
   board, the VPN permission prompt, a connected room and Settings are enough.
3. **App Review Information**, first line: "This is a client for standard VPN
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
