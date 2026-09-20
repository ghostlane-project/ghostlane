# App Store submission — what is done and what is not

Written 2026-07-28, against `main`. The point of this file is that the next
person to attempt a submission does not rediscover the same six things.

## Done in the repository

- **No self-update on iOS.** The release feed is not queried, the "Update
  available" sheet cannot appear, and nothing points a user at a download page.
  An App Store build whose version numbers disagree with the store's, telling
  people to install it from elsewhere, is a rejection.
- **No unused background mode.** `UIBackgroundModes: audio` was declared for a
  silent-audio keep-alive that no longer runs at all. Declaring a mode the app
  does not use is 2.5.4.
- **`NSCameraUsageDescription`** is present and says what the camera is for: one
  QR code, nothing recorded.
- **`PrivacyInfo.xcprivacy`** declares the one required-reason API this app uses
  (`NSUserDefaults`, reason `CA92.1`) and that it collects nothing and tracks
  nobody. **It must be a member of the app target** — a file on disk that
  nothing references is not in the bundle. Check it in Xcode's File Inspector
  before the first upload.
- **Diagnostics are off.** `MemoryWatch.enabled` and olcRTC's verbose engine log
  are both false; each was a switch that earned its place during a specific
  investigation and costs real work in a process with a ~50 MB ceiling.
- **Admin plumbing fails closed.** The per-location configurator and "create
  custom location" appear only in a build with `OLCBOX_ADMIN_PASS_SHA256` baked
  in, and only once seven taps and the password have opened it. Forgetting the
  secret now hides them rather than showing an operator's edge addresses, SNIs
  and certificate pins to every customer.

## Decisions that are yours, not the code's

- **Export compliance.** Answered: `ITSAppUsesNonExemptEncryption = false` is in
  `Info.plist`, on the publicly-available-source route of EAR §742.15(b) — see
  `app-store-listing.md`. **That route has a precondition that is not yet met:**
  the notification email to `crypt@bis.doc.gov` and `enc@nsa.gov` has not been
  sent, and until it is, the `false` is a false declaration on a customs
  question. The template is in `app-store-listing.md` and needs only a real
  contact name, email and telephone. The claim it makes — that the corresponding
  source is public — holds: `romanpodpriatov/ghostlane` and `romanpodpriatov/olcrtc`
  both answer to an unauthenticated request, and they must stay that way.
- **The signing identity.** Guideline 5.4 requires VPN apps to come from an
  organisation account, which Team `3QJG3J7L66` (Globvent inc) is.
- **Privacy policy URL** and support URL, both required by the listing.
- **What the listing says.** Screenshots, description, age rating, category.

## Before the first upload

1. Build with the admin hash set if you want the gate, and verify on a device
   that seven taps on the title are needed before the configurator appears.
   Revised 2026-08-04: an ungated build is no longer refused. It reads like the
   more dangerous option and is the opposite — `plumbingVisible` requires the
   gate to exist *and* be open, so no hash means the per-location editor and
   "create custom location" are hidden permanently. What an ungated build shows
   that a gated one hides is the SOCKS5 proxy row. The build still refuses
   without `olcbox.cryptKeyV1`, because that one breaks crypt1 link import while
   looking like a bug.
2. Confirm `PrivacyInfo.xcprivacy` is in the app target's Copy Bundle Resources.
3. Bump `CFBundleVersion` for every upload; App Store Connect rejects a repeat.
4. TestFlight first. The tunnel behaves differently under a store-signed
   provisioning profile than under a development one, and the first place to
   discover that is not review.

## Where the submission stands (2026-08-02)

Submitted as **1.0.0 (1)** and rejected under **2.1.0 App Completeness** — not on
substance, but an automated request for the VPN answers: what user information
the app collects, for what purpose, and whether it is shared with third parties.
Answered in App Review Information and by replying to the message; the same
binary was resubmitted, no new build needed.

- **Availability** excludes China, UAE, Oman and Turkey (VPN licensing).
- **Distribution must stay Public.** It was briefly set to Private, which would
  have kept the app out of the public App Store entirely, and approval is the
  last moment at which that can be changed.
- **Privacy policy: `https://proofkit.org/privacy-policy`** — the full slug. The
  site answered 200 to any unknown path, so a listing pointing at `/privacy`
  rendered the landing page and looked fine to whoever pasted it while Apple and
  every user following it missed the policy. Since 2026-08-02 `/privacy` (and
  `/terms`, `/tos`, `/cookies`, `/refunds`, `/dmca`, `/aup`) redirect to the real
  page.

## For the next version

1. **Bump the version in Xcode's General tab** (`MARKETING_VERSION` /
   `CURRENT_PROJECT_VERSION`). This works only since `8bb32ab`: `Info.plist`
   carried the literals `1.0.0` and `1`, which silently outranked the build
   settings, so an archive taken after a bump came out `1.0.0 (1)` again.

   > That change then broke the iOS release build, because
   > `CURRENT_PROJECT_VERSION` was set on the PacketTunnel target and *not* on
   > the app target. **Xcode omits an `Info.plist` key whose `$(BUILD_SETTING)`
   > is undefined**, so `CFBundleVersion` disappeared from the built app and CI
   > failed on `Set: Entry, ":CFBundleVersion", Does Not Exist`. Both targets now
   > carry it, and the packaging step checks both keys exist and says why if they
   > do not. A local archive would have failed the same way, at upload.
2. **Ship the VPN disclosure screen.** `VpnDisclosureScreen` is on `main`
   (`85cf9dc`) and shown before the first connection, but it is *not* in the
   submitted build. It is what Guideline 5.4 and Play's prominent-disclosure rule
   ask for.
3. **Send the BIS/NSA notification first** if it has not gone yet — see
   "Decisions that are yours" above. It is a precondition of the export answer
   already baked into every upload.
4. **The two version numbers still disagree.** The store says `1.0.0`; the app
   shows `1.0.24x` from `GeneratedAppInfo` (Gradle `olcbox.version`). Align them
   after approval, ideally by taking the number from the same source Android
   does.

An Xcode **Release** build now fails outright when `olcbox.cryptKeyV1` or
`olcbox.adminPassSha256` is missing, rather than logging one line into a
transcript nobody reads and shipping without crypt1 link import or the admin
gate. Debug builds are unaffected.

## Known and deliberate

- **No latency for an unconnected Reality/Hysteria2/XHTTP exit beyond ICMP.**
  Their cores live in the tunnel extension, which runs one location at a time,
  and they cannot be linked into the app beside olcRTC's framework — two
  gomobile binds in one binary is fifty duplicate symbols. ICMP measures the
  path, which is what "ping" has always meant, and every node tested answers it.
- **`rememberModalBottomSheetState` is deprecated and suppressed** at four call
  sites. Its replacement is itself alpha in the pinned material3; migrate all
  four together, deliberately, not during a release.

## The 3.1.1 rejection, and the reply

Second rejection, 2026-08-03, submission `06686da6`, reviewed on an iPad Air:
**Guideline 3.1.1 — the app "accesses digital content purchased outside the app…
but that content isn't available to purchase using In-App Purchase."**

What they tapped was "Get a subscription — Open ProofKit to create one", in the
add-configuration sheet and on the setup screen, which on iOS opened
proofkit.org. However it is worded, that is a call to action for a purchase
outside IAP. It is gone from the iOS build (`showGetSubscription = false`); the
other platforms keep it, because this is Apple's rule and not a change of
product.

The alternatives were weighed: In-App Purchase would put Apple between us and
per-gigabyte billing denominated in TON, and the US external-link entitlement
buys one storefront while every other still requires IAP. Both are decisions
about the business; removing a button is not.

**Reply to send in App Store Connect.** Every line was checked against the
source — `siteUrl` had exactly one use on iOS and this was it, and the app has no
account, balance, prices or wallet anywhere:

> The app does not sell anything and contains no button, link, or other call to
> action for any purchase. The control you identified — "Get a subscription",
> which opened our website — has been removed and is not present in this build.
>
> ProofKit is a VPN client. It connects using configuration the user already
> has: a subscription URL, a QR code, or a pasted server link, imported the same
> way as in other VPN client apps. The app contains no account, no sign-in, no
> balance, no prices, and no wallet, and it unlocks no functionality upon any
> purchase — every feature is available to anyone who supplies a server
> configuration, including servers we do not operate.
>
> We are not requesting an exception under 3.1.3(b); we are removing the purchase
> pointer entirely.

Deliberately not argued: whether access to our service is "paid digital content"
at all. It might be arguable and it costs a review round, which usually ends in
"then add IAP". Removing the pointer costs nothing.

The build to send is `1.0.0 (3)` — the version numbers are already bumped in the
project, so the archive carries it without anyone editing Xcode. It also carries
`VpnDisclosureScreen`, which Guideline 5.4 asks for and which the submitted
`1.0.0 (1)` did not have.
