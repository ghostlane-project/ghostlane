# Reply to App Review — Guideline 3.1.1 on 1.0.429, third round (submission 9ed9259f)

Date: 2026-09-16. Reviewed on an iPad Air 11-inch (M3). Thread so far: 2.1(b)
questionnaire (Sep 15) → our business-model reply (Sep 15) → 3.1.1 rejection
(Sep 16) with a **Bug Fix Submissions** offer.

## What the reviewer concluded, in their words

> The app accesses digital content purchased outside the app, such as adding
> connection from a provider's list, but that content isn't available to purchase
> using In-App Purchase.

That is the textbook 3.1.3(b) reading: content bought elsewhere, consumed in the
app, no IAP parity. Where it came from:

1. **Our own 2.1(b) reply described the marketplace in full** — wallet, escrow
   deposit, per-gigabyte metering, a server-list URL issued to the customer. To a
   reviewer that paragraph *is* "digital content purchased outside the app". The
   3.1.3(f) framing was in the same message, but (f) names VoIP, cloud storage,
   email and hosting, and a VPN service sold by the app's own developer is read as
   (b) unless something forces the (f) reading.
2. **Every listing URL still leads to the pay-per-GB site** (Marketing `proofkit.org`,
   Support `/help`, Privacy `/privacy`). The app is called ProofKit VPN. The
   review server list came from that site. The tie between app and paid service is
   the reviewer's whole case; nothing in the binary is.
3. **What did not help**: "it is free", "no purchase path", "no account". 3.1.1
   bites exactly on free apps that unlock content bought elsewhere; "no account"
   is our best fact and was not made the centre of the argument.

Nothing in the app changed since **1.0.301, which is on the store with the identical
model**. That is the strongest sentence we have and it was not said.

## Two moves, in this order

### Move 1 — today: take the Bug Fix Submissions offer

Apple wrote: *"If this submission includes bug fixes and you'd like to have it
approved at this time, reply to this message and let us know."* 1.0.429 is a bug-fix
release from end to end (packet-extension memory deaths, olcRTC relay desync,
half-open connections, app memory in the background). Say so, ask for approval,
and keep the guideline discussion out of that message: a reply that argues is a
reply that waits.

Paste as-is:

```
Thank you. Yes — this submission consists of bug fixes, and we would like it
approved at this time under the bug fix submission process.

What changed from the approved 1.0.301 to 1.0.429 (429):
- Packet tunnel extension: fixed three classes of termination under memory
  pressure (the Go memory limit was being overridden by an engine started later;
  the xhttp transport now runs behind a C tun2socks with bounded HTTP/2 windows;
  UDP association buffers were cut from 64 KB to 16 KB with a single sweeper).
- olcRTC engine: fixed a relay desynchronisation after a lost frame and a
  half-open connection leak that grew the goroutine count over hours; DNS now
  travels over the tunnel's reliable stream instead of the lossy datagram lane.
- App: memory is released when the app goes to the background; a leaked overlay
  view is removed on scene detach.
No feature, screen, string or business-model change is included. The app still
has no account, no sign-in, no purchase path and no In-App Purchase.

We will address the Guideline 3.1.1 note separately for the next update, and we
will reply in this thread with the information about how the guidelines apply.
```

### Move 2 — same thread, after approval: answer the 3.1.3(b) reading, once

Apple invited it: *"If you have any additional information … how the guidelines
apply to them, please reply."* Answer in one message, then stop; the next step
after that is the App Review Board, not a fourth message.

```
On Guideline 3.1.1, for the next update — three facts we would like on record.

1. The app has no account. Guideline 3.1.3(b) describes content a customer
   "acquired" and then accesses in the app by signing in; this app cannot sign
   anyone in, has no user identity, no balance, no quota, no entitlement check.
   It imports a list of server addresses and credentials in the standard formats
   every VPN client uses (VLESS, Hysteria2, XHTTP links) and connects. It does not
   know whether a list was paid for, free, or written by hand, and it behaves
   identically in each case. There is nothing in it to make available "for
   purchase using In-App Purchase": the app sells nothing, unlocks nothing and
   cannot tell one provider from another.

2. The model is unchanged from 1.0.301, the version currently on the App Store.
   1.0.429 differs from it only in bug fixes. The list we supplied for review
   carried the operator's per-gigabyte rate in each server's name; that label
   was a mistake on our side and has been removed from the list, not from the
   app, because the app never displayed prices of its own.

3. Our reading of the guidelines: a free stand-alone client under 3.1.3(f). The
   service some of our users pay for is bought and managed entirely on a website
   the app never opens, mentions or links; there is no purchasing inside the app
   and no call to action for purchase outside it. The app is equally a client for
   any other provider's servers, and it is the only App Store client for olcRTC,
   an open-source transport that carries traffic inside a WebRTC call.

If review holds that 3.1.3(b) applies, we would ask which item the app is
understood to unlock, since an In-App Purchase requires something to be unlocked
by it. We are glad to provide a screen recording of import, connect and status.
```

### Then: App Review Board appeal, if the thread answers (b) again

Contact Us → App Review → appeal a rejection. Same three facts, one paragraph
each, plus: the open-source links, the note that 1.0.301 is live with the identical
model, and the recording. Do **not** cite other developers' clients by name — that
is how the 4.3(a) round started (see `app-review-4.3-reply.md`).

## Before the next submission (1.0.434 or later): cut the trail

The reviewer's case is built from the listing, not the binary. Three edits in
App Store Connect, all outside review:

| Field | Today | Change to |
|---|---|---|
| Marketing URL | `proofkit.org` — "Pay-per-GB … Marketplace on TON", "Open App" into the paid web product | `https://proofkit.org/app` (client page: olcRTC, transports, import, open source, **no price, no purchase**) or the GitHub repo |
| Support URL | `proofkit.org/help` — operator help, "set per-GB pricing" | a client help page (import, connect, transports, diagnostics export) with no pricing |
| Privacy Policy URL | `proofkit.org/privacy` — "bill pay-per-GB usage" | keep, or a client policy: the app itself collects nothing; the line about billing is true of the website, and the reviewer reads it as the app's |

Plus the App Review Information note, first line: *"This is a client for standard
VPN protocols; the server list below is a free test configuration issued for
review. The app has no account, no purchase path and no In-App Purchase."*

Make these edits **after** the current submission is closed. Swapping URLs while
review is reading them looks like hiding.

## The structural option, for the user to decide

Every round so far was won on words and lost again on the next reviewer, because
the app carries the marketplace's name and the marketplace's URLs. The exit that
stops the cycle: publish the App Store app as the open-source client it already
is — **olcbox**, marketing URL GitHub, support on GitHub issues, privacy policy for
the client alone — and let proofkit.org be one provider whose lists work in it,
exactly like in any other client. No code change. It gives up the ProofKit name
on the store, and a rename of a live app is a metadata edit, not a new listing.

The alternative that also ends the cycle is In-App Purchase for gigabyte packs
(3.1.3(b) parity: the same thing sold in the app through StoreKit). It means
Apple's commission on a marketplace that keeps 20 %, a custodial balance next to
the escrow, and a StoreKit + coordinator project. Declined before
(`app-store-submission.md`); it stays declined unless the user reopens it.
