# Reply to App Review — Guideline 4.3(a), submission 6640c983

Rejected 2026-08-12 on 1.0.270 (271), reviewed on an iPad Air 11-inch (M3).

Paste the block below into the App Store Connect message thread. Send it **with a
rewritten listing** — see `docs/app-store-listing.md`. The listing is the part that
actually caused this, and a reply that arrives against the old metadata repeats the
mistake that kept 3.1.1 alive for two rounds (`docs/app-review-3.1.1-reply.md`).

## Send a new build. Do not resubmit 1.0.270.

A rejected version *can* be resubmitted on the same binary with changed metadata, and
this document said to do that. That was wrong, and here is why.

1.0.270 was stamped on 2026-08-06 (`6c3abe5`) and resubmitted on the 7th. Three things
this reply depends on landed **after** it:

| | | in 1.0.270 |
|---|---|---|
| `52a8e39` `b5eb547` `6b30d10` | occupancy rendered at all, parsed, and re-asked | **no** |
| `6c25303` (08-08) | olcRTC rooms are not pingable | **no** |
| `646864a` (08-07) | "subscription" → "server list" | landed the same day as the resubmission; which side of the archive it fell on is not knowable from the repo |

So on the binary review currently holds, the server list has **no occupancy bars**, and
every olcRTC row reads **Offline** — including the one carrying the connection, because
`canPing` returned true for olcRTC and `ping()` for an olcRTC config joins the room as a
real client, which cannot succeed while the tunnel is up. Null renders as Offline.

The review notes and the reply below both tell the reviewer to select the first olcRTC
entry and watch its live occupancy. On 1.0.270 they would find no bar and a list of dead
servers. That is Guideline 2.1 stacked on top of the open 4.3, self-inflicted, and it
would arrive with a message from us claiming the opposite.

**Ship a build from current `main`, then send this.**

---

## Why this happened, so the reply does not miss it

4.3(a) is about looking like everything else. What review could see was:

- **Subtitle** `Reality, Hysteria2 and XHTTP` — the protocol list every client in the
  category prints.
- **Keywords** containing `sing-box` and `xray` — the names of the two engines that
  this app *shares with the cluster it was mistaken for*. We put the similarity into
  the metadata ourselves.
- **Description** opening «ProofKit connects to a VPN subscription you already have.
  It is a client, not a service.»
- **The repository** linked as OPEN SOURCE, whose tagline read «A VPN client for
  servers you already have» and whose README opens by naming the project it forked.
- **Screenshot 1**: an empty state, `Import a server list to start`. First impression
  of the app is a blank box waiting for someone else's configuration.

And our own reply of 2026-08-07, sent to escape 3.1.1, said in writing: «There is no
check that a server belongs to us, no allowlist of hosts... A configuration from an
unrelated provider works identically.» True, and precisely the definition of the
thing 4.3(a) exists to remove.

**There is no contradiction to manage.** The 3.1.1 answer already contained the 4.3
answer, in one sentence near the end: *no existing client speaks olcRTC*. This reply
makes that the headline. It introduces no purchase, no account and no service
dependency, so it does not reopen 3.1.1.

**Verified before writing this:** `alananisimov/olcbox` is not on the App Store. It
publishes GitHub CI builds only. There is no twin app to point at.

---

## The reply

```
Thank you for the detail. We would like to show you what this app does that no
other app does, and to correct our own App Store listing — which described the
app in the generic terms of its category and is, we think, the reason it read as
one of many.

WHAT IS UNIQUE HERE: olcRTC

The reason this app exists is a transport called olcRTC. It carries the device's
traffic inside a WebRTC media session — an ordinary video call to a public
meeting service — so that on a network which identifies and drops every VPN
protocol by signature, what remains on the wire is a video call the network
already permits.

This is not a library that can be dropped into a template. It needs a matching
relay on the other side, negotiation of a room and a slot within it, and a client
able to hold a live media session open while a packet tunnel rides inside it. We
maintain the transport engine ourselves and publish it:

  github.com/ghostlane-project/olcrtc

This app is its only implementation on iOS, and to our knowledge the only
implementation of olcRTC on the App Store in any form.

Functionality that exists in this app and, as far as we know, in no other VPN
client on the store:

- Relay rooms with live occupancy. A room holds a fixed number of slots and a
  full one cannot accept you, so the app asks each room how full it is and shows
  that in the server list before you choose. No other client has the concept,
  because no other client connects to something that has rooms.
- Latency reported only where it can honestly be measured — through the tunnel
  for the connection you are on, by ICMP echo for one you are not. Where neither
  is possible the app says so rather than printing a guessed number.

ON THE CODEBASE

We are open about lineage rather than hiding it: the app is built on the
open-source olcbox client, and our README says so in its first paragraph.

That project is not on the App Store. It ships GitHub CI builds only, its author
has not submitted it, and there is no app on the store built from it. Since the
fork this app is 299 commits of our own work, including the olcRTC engine
integration, the iOS packet tunnel (the upstream project had no App Store iOS
target at all), a second and third protocol core, our own interface and icon, and
log scrubbing so that exported diagnostics cannot leak a user's destinations.

Everything above is checkable against the source:

  github.com/ghostlane-project/ghostlane

WHY IT ALSO SPEAKS THE STANDARD PROTOCOLS

A call-shaped tunnel costs bandwidth and latency, and it is not needed until the
ordinary ones stop working. So the app connects over the standard transports as
well, and the user moves between them as the network changes. The moment someone
needs the fallback is the worst possible moment to be installing a second app.

We understand that supporting those protocols is what made the app look like the
others, and that our listing led with them. That was our error in describing it,
not what the app is for.

WHAT WE HAVE CHANGED

We have rewritten the App Store listing so it describes this app rather than its
category. The subtitle and description now lead with olcRTC and what it does. We
have removed the keywords naming the shared protocol engines, and the screenshots
now open on a working olcRTC session instead of an empty import screen.

HOW TO SEE IT WORKING

The server list in App Review Information contains an olcRTC location as its
first entry. Selecting it and tapping START opens the media session and routes
the device through it; the status screen shows the session running. The occupancy
bar on that entry is live and will change as slots are taken.

We are glad to supply a video of the olcRTC session being established, a written
walkthrough of the transport, or anything else that would help. If there is a
specific app you believe this one duplicates, we would be grateful to know which
— we are not aware of one and would like to address it directly.
```

---

## Before sending

- [ ] Paste the rewritten Subtitle, Promotional text, Description and Keywords from
      `docs/app-store-listing.md`. **The reply claims the listing has changed; if it
      has not, this fails the way 3.1.1 failed.**
- [ ] Reorder the screenshots so slot 1 is a connected olcRTC session, and replace
      the empty state. Screenshots are metadata and are reviewed.
- [ ] Check the App Review Information server list is live, and that its **first**
      entry is an olcRTC location with free slots. The reply tells them to select it.
- [ ] Update the repository tagline on GitHub. It currently reads «A VPN client for
      servers you already have» — the category sentence, on the page the listing
      sends a reviewer to. Lead with olcRTC there too.
- [ ] Keep the README's fork acknowledgement. Removing it after review has seen it
      is worse than the thing it would hide, and the reply relies on being open
      about it.
- [ ] Do not add a purchase pointer anywhere while fixing this. The 3.1.1 rejections
      cost two rounds and the cause was one sentence in the description.

## If it comes back

In order:

1. **Reply again asking which app it duplicates.** 4.3(a) is sometimes raised by
   automated similarity scoring; a named twin can be answered, an unnamed one cannot.
2. **Ship the build changes** (needs a new binary, so CI + signing):
   - first run opens on olcRTC rather than a generic empty state;
   - the connected screen names the transport and, for olcRTC, shows the room;
   - `Import a server list to start` replaced with something that says what this app
     is for.
3. **App Review Board appeal**, with the video and the source links. Escalate only
   after the thread has been tried — an appeal against unchanged metadata loses.
