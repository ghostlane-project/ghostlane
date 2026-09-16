# Reply to App Review — Guideline 2.1(b) "Information Needed", submission 9ed9259f

Received 2026-09-15 on **1.0.429 (429)**, reviewed on an iPad Air 11-inch (M3). Not a
finding against the binary: review paused and sent the standard four-question
business-model questionnaire, because "it appears the app may access or include paid
digital content or services". The phrase they quote back at us is **"Pay per GB"**.

## Where "Pay per GB" came from — two places, and one of them is inside the app

**1. The server list we gave them shows a price on every server.** The review notes
carry `https://proofkit.org/sub/<token>`. The coordinator names each entry from
`coordinator/src/services/vless.rs` (`build_session_sub_links`) as

```
AU Direct | 0.0120TON/GB · Hysteria2
DE via RU | 0.1200TON/GB · XHTTP
```

Decoded on 2026-09-15: 88 entries, every one of them labelled with the operator's
per-gigabyte rate in TON, grouped in the app under the list's host, `proofkit.org`. That
is a price list, rendered by the app, next to a "connect" button. The olcRTC entries
(`JP · olcRTC`) carry no price — the reviewer was told to select the first of those, but
the whole list is on the same screen. Nothing else in the app says anything about money:
the olcbox source has zero hits for "per GB", the only money words are the onboarding
line "No account, nothing to buy, nothing collected", and the coordinator sends no
`subscription-userinfo` header, so there is no quota or balance either. **The labels are
the one thing that had to go, and they had to go before the reply is sent.**

**2. The website.** Three listing URLs point at it and each one says it:

| listing field | URL | what a reviewer reads there |
|---|---|---|
| Marketing URL | `proofkit.org` | `<title>` **"ProofKit — Pay-per-GB Decentralized VPN Marketplace on TON"**; "PAYMENTS · Pay-per-GB. Withdraw the rest."; "Open App" |
| Privacy Policy URL | `proofkit.org/privacy` → `/privacy-policy` | "…deliver and meter the VPN service, **bill pay-per-GB usage**, settle operator payouts…" |
| Support URL | `proofkit.org/help` | "deposit flow, subscription URL setup", "set per-GB pricing" |

The `/sub/<token>` URL itself, opened in Safari, is `text/plain` base64 — no page, no
prices visible — but it puts the marketplace's domain in front of the reviewer.

So the reviewer held an app called ProofKit, imported a list from proofkit.org whose
every row quoted a TON-per-gigabyte rate, and opened a site where ProofKit sells VPN
bandwidth by the gigabyte for TON. The question 3.1.1 exists to ask followed: is this a
free client for something bought outside In-App Purchase? A fair question; it deserves a
straight answer, not a denial — they have read both the list and the site.

## The line to hold

Three rounds have settled what this app is, and the reply must not drift from any of them:

- **3.1.1 (August):** the app contains no purchase, no account, no balance, no wallet,
  and no button or link to any purchase. *Still true, re-checked against the source today
  — with one correction: the app did display prices, because the list carried them.*
- **4.3(a) (August):** the app exists for olcRTC, which no other App Store app implements;
  the standard protocols are the fallback. *Still true.*
- **Now:** yes, ProofKit the company runs a paid web marketplace; the app is its free
  stand-alone client, and also a client for anyone else's servers. That is
  **Guideline 3.1.3(f)** — "Free apps acting as a stand-alone companion to a paid web
  based tool (i.e. VoIP, Cloud Storage, Email Services, Web Hosting) do not need to use
  in-app purchase, provided there is no purchasing inside the app, or calls to action for
  purchase outside of the app."

**Do not go near 3.1.3(b).** "Multiplatform Services" permits access to things bought on
the web *only if the same items are also sold as In-App Purchases*. A reply that describes
the app as "access to your ProofKit purchases on iOS" hands review that clause, and the
next message is "then add IAP". The August reply already said we are not requesting an
exception under 3.1.3(b); this one says it again.

**A price displayed in the app is a call to action in Apple's reading**, whatever the
guideline text says about "buttons and links": that is what the 2024 Spotify dispute was
about, and it is what the reviewer saw here. The list must stop carrying prices before
the reply goes, so that the reviewer's re-test of the same binary shows none.

## The fix that has to land first — landed, `ca908f06` on proofkit `main` (2026-09-15)

Drop the rate from the tag in `coordinator/src/services/vless.rs`: `DE Direct · Hysteria2`
instead of `DE Direct | 0.0600TON/GB · Hysteria2`. For every client, not just this app —
a label that changes with the User-Agent is a story nobody wants to tell review, and the
app's list fetch sends a Chrome User-Agent in one of its two modes anyway. The rate is
redundant where the list is consumed: a user's list contains only the routes they chose
on the website, where the price is shown, and the price is locked into the session at
connect. Tests to update: `test_subscription_tag_format` in `vless.rs`, the sample tag
in `services/qr.rs`. Deploy through CI, then re-fetch the review token's list and check
no entry name contains `TON/GB` before sending anything. Done as `session_tag()` in
`vless.rs` (pure, unit-tested; the price column left the link query with it).

Alternative, if the marketplace wants to keep prices in third-party clients: strip our own
` | <n>TON/GB` suffix in olcbox when rendering. That needs a new build (1.0.430) and ties
the client to the coordinator's label format; the reviewer then re-tests a new binary.

## The reply

Paste into the App Store Connect message thread, **after** the list has been re-fetched
and shows no prices. **The thread field takes 4000 characters at most**; this text is
3692 characters. Do not add to it without cutting elsewhere.

```
Thank you for the questions. Two points first, then the answers.

The app is a general VPN client. It connects to any provider's servers that speak VLESS/Reality, Hysteria2, XHTTP or olcRTC, from a server list the user adds by link, QR code or file. There is no allowlist of hosts, no account and no tie to our website: our own marketplace is one provider among many, and any other provider's list works identically. What no other App Store client has is olcRTC, our open-source transport that carries traffic inside a WebRTC video call.

"Pay per GB" came from the server list we supplied for review. It is issued by our website, proofkit.org, a marketplace where independent operators sell VPN bandwidth by the gigabyte, and it labelled each server with the operator's rate ("DE Direct | 0.0600TON/GB"). The app displayed those labels as it displays any list. We agree a rate next to a server reads as a paid service, and we have removed it: the list now names servers by country and protocol only, and the app shows no price anywhere. Re-importing the list in App Review Information shows this on the build under review; no app code changed, because the app never had pricing of its own. Our website (the Marketing and Privacy Policy URLs) describes per-gigabyte billing because that is how the web service works. The paid part lives entirely on the web; the app is a free client with no purchase path: no account, no sign-in, no balance, no wallet, no In-App Purchase, no button or link to any purchase.

1. Who are the users of the paid Pay per GB in the app?
Nobody: the app has no such feature. Its users are anyone with a server list from any provider that speaks these protocols. Some are customers of our web marketplace, many are not, and the app works identically for both.

2. Where can users purchase it?
Only on the website, outside the app. A marketplace customer connects their own TON wallet on the website and deposits into a public escrow contract; the marketplace meters traffic per gigabyte server-side and issues a server-list URL. The app takes no part: it does not open, mention or link to the website and cannot buy, top up or show a balance. A link that opened our website existed in the first build (August) and was removed in that review round; no build since has had one.

3. What previously purchased Pay per GB can a user access in the app?
None. The app cannot access purchases, plans, balances, quotas or accounts. A user brings only a server list (addresses and credentials in the standard formats every VPN client uses). The app does not know or check who issued it, shows no balance, quota or price, and behaves exactly the same for any provider.

4. What paid content, subscriptions or features are unlocked without In-App Purchase?
None. Nothing is locked. Every feature, olcRTC included, is available to every user without any purchase, code, key or account. There is no In-App Purchase because there is nothing in the app to sell.

We are not requesting an exception under 3.1.3(b). We understand the app to be a free stand-alone companion to a paid web-based service under Guideline 3.1.3(f), like a VoIP or cloud-storage client: the service is bought and managed entirely on the web, the app contains no purchasing and no call to action, and it is also useful without our service at all. If you read it differently, please tell us which guideline you have in mind.

The app is open source: github.com/romanpodpriatov/olcbox. The server list in App Review Information is a free test configuration issued for review; nothing was bought to create it. We are glad to provide a screen recording of import, connect and status, or anything else that helps.
```

## Before sending

- [x] **The coordinator change is deployed and verified** (2026-09-15 01:49 UTC, both APPs
      restarted on `ca908f06`; the review token's `/sub/<token>` decoded on each APP and
      through the LB: 88 entries, 0 labels with `TON/GB`, e.g. `AU Direct · Hysteria2`).
      Re-check right before sending: the reply says the prices are gone, and if a reviewer
      re-imports and they are not, this is the 3.1.1 mistake of August all over again.
- [ ] **Fix one sentence in the description.** REQUIREMENTS says "ProofKit does not sell
      one and does not include one" — to a reviewer who has just read proofkit.org,
      ProofKit plainly does sell one. `docs/app-store-listing.md` now reads "The app does
      not sell one and does not include one"; paste that version into App Store Connect.
- [ ] Send the reply in the thread of submission `9ed9259f`. **No new build.** The binary
      was not faulted; the review is paused on the questionnaire. Once the description is
      edited, resubmit the same 1.0.429 (429) so the metadata change travels with the reply.
- [ ] Do not add an In-App Purchase, a price, a balance, or a link to the website to the
      app in response to this. Any of them turns a 2.1(b) question into a 3.1.1 finding.
- [ ] App Review Information notes still say: no account, no sign-in, no purchase; the
      server list is a free test configuration. Leave them.
- [ ] The review server list is live, and its **first** entry is an olcRTC location with
      free slots — the notes and the 4.3 reply both tell the reviewer to select it.
- [ ] `grep -rni subscription iosApp --include=*.plist --include=*.swift` is 0 and there is
      no "per GB" anywhere in the listing (both true on 2026-09-15; keep them true).
- [ ] **Screenshot `03-connected.PNG` (shot 2026-09-08) shows a quota bar:** `PLAN · RESETS IN
      2913D  4.3/5.0 GB`, rendered from a third-party list's `subscription-userinfo`
      header under the group `@Xrayvlesspaym…`. In a store screenshot that is a plan with
      a data allowance, next to the word PLAN — the same reading that made "subscription"
      cost two rounds. If this set is what App Store Connect holds, replace that frame
      with one shot on the ProofKit list (the coordinator sends no quota header, so no
      bar appears) — metadata only, no build. `00-intro`, `02-vpnpopup` and `04-settings`
      carry no money words (checked). For a later build: the label itself
      (`LocationSelection.kt`, "Plan · resets in Nd") should become "Traffic", so a
      provider's quota never reads as a purchased plan.

## Optional, after this round: give the listing an app page

Every future reviewer who opens the Marketing URL lands on a page whose title is
"Pay-per-GB … Marketplace" with an "Open App" button into the paid web product. That will
raise this question again. The Marketing URL is optional and is meant to be about the app;
a page such as `proofkit.org/app` that describes the client — olcRTC, the transports, the
server-list import, the open-source link — with no prices and no purchase flow, is the
honest fix. The Privacy Policy URL stays where it is: the policy is shared with the web
service and must remain true, and "bill pay-per-GB usage" is true.

Do **not** change the Marketing URL *during* this round. Swapping the site out after
review has read it looks like hiding it, and the reply above relies on being open.

## If it comes back

In order:

1. **"3.1.3(b) requires the items to be available as IAP."** Answer that (b) concerns
   items acquired *in your app* on other platforms; there is no ProofKit purchase in any
   app on any platform — the marketplace is a website, the deposit is made from the
   user's own wallet into a contract we do not control, and the iOS app is the same
   generic client that works with any provider. Restate 3.1.3(f), name the VoIP and
   hosting analogies, and ask what item they believe the app is unlocking.
2. **"Remove all reference to the paid service."** There is none left in the app; the
   references are on the website the listing links to. Offer the app page above as the
   Marketing URL, and point out the Privacy Policy is required to describe the service
   that collects the data.
3. **App Review Board appeal**, with the open-source links and the recording. Only after
   the thread has been answered twice: an appeal against an unanswered question loses.
4. **The business decision** — In-App Purchase for per-gigabyte TON billing — is
   documented in `docs/app-store-submission.md` under the 3.1.1 rejection and was
   declined. It is not a change to make in a reply.
