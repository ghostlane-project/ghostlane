# Reply to App Review — Guideline 3.1.1, submission 6640c983

Paste into the App Store Connect message thread. Written to remove the cause rather
than argue the guideline: review saw a word that means one thing to us and another to
them, and the fastest way through is to change the word and say so.

Send it **with** the new build and the corrected description — the previous reply was
true about the binary while our own store listing still advertised the pointer we said
we had removed, which is why nothing looked fixed from their side.

---

```
Thank you for the detail — it let us find what the app was showing you, and we
have changed it.

WHAT YOU SAW, AND WHY IT WAS MISLEADING

The app labelled things "subscription". In VPN client software that word means a
URL that returns a list of servers — a configuration feed, the same thing other
clients call a profile or a config. It is not a paid plan, and nothing in this
app is unlocked by paying for one.

We understand how it read. The first screen said "Import a subscription to
start" and the settings menu offered "Subscription Settings", in an app with no
In-App Purchase. We have renamed every user-visible instance: the app now says
"server list" throughout. There is no longer any wording in the app that could
be read as referring to a paid subscription.

WHAT THE APP DOES AND DOES NOT CONTAIN

ProofKit is free. It contains no In-App Purchase, no account, no sign-in, no
prices, no balance, no wallet, and no button or link that leads to any purchase
anywhere. No feature is gated behind a payment: every capability is available to
anyone who supplies a server configuration.

The "Get a subscription" row identified in the earlier review was hidden on iOS
in the build you reviewed. It is now deleted from the source entirely, on every
platform.

We also found the remaining cause on our side. Our App Store description still
ended with a sentence offering to open our website to obtain a subscription. That
contradicted what we told you on 4 August, and we have removed it. We apologise —
from your position nothing had changed, and that was our error, not a
misunderstanding on yours.

IT IS NOT TIED TO ANY ONE PROVIDER

The app reads standard configuration links — VLESS with Reality, VLESS over TLS,
Hysteria2, XHTTP, and olcRTC — from any source. There is no check that a server
belongs to us, no allowlist of hosts, and no account that ties a user to us. A
configuration from an unrelated provider works identically, and the app has no
way to tell the difference.

The reason we built it is technical: no existing client speaks olcRTC, a
transport that carries traffic inside a video call, and none supports this
combination of protocols in one place. That is what the app is for.

The access we supplied for testing is a free test configuration provided for
review. It is not a purchase and was not bought.

VERIFIABLE

The app is open source: github.com/ghostlane-project/ghostlane — every claim above can
be checked against the code, including the absence of any purchase path.

We are not requesting an exception under 3.1.3(b). There is no paid content in
this app to make available through In-App Purchase.
```

---

## Before sending

- [ ] Paste the corrected Description from `docs/app-store-listing.md` into App Store
      Connect. The REQUIREMENTS paragraph must no longer offer to open proofkit.org.
      This is the one that made the last reply look untrue.
- [ ] Upload a build containing the rename. Sending this text against 1.0.270, which
      still says "Import a subscription to start", repeats the same mistake.
- [ ] App Review Information: state that the supplied configuration is a free test
      credential, and that the app has no account and no purchase.
- [ ] Check the screenshots. If any of them shows the old "subscription" wording,
      replace it — screenshots are metadata and are reviewed too.
