# Google Play listing — copy, answers, and the declarations that decide it

Everything here is ready to paste. English only, for the same reason as the App
Store listing: the app has no translations, and a localised listing over an
untranslated app reads as bait.

Character limits are Google's and are enforced; counts in brackets are what the
text below actually uses. Checked against the console form and the 1.0.397
bundle on 2026-09-11; the `play` flavor and the CI upload landed the same day.

---

## Create app — the first form

| Field | Value |
|---|---|
| App name | `Ghostlane` — matches `android:label` in `androidApp/src/main/AndroidManifest.xml` |
| Package name | `org.proofkit.app` — `applicationId` in `androidApp/build.gradle.kts`. Fixed for the life of the app; a bundle with any other id is refused at upload |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free. Free → paid is impossible once published; nothing is sold in the app anyway |
| Declarations | Both boxes: Developer Program Policies, and US export laws |

> The export-laws box is the question `ITSAppUsesNonExemptEncryption` answers on
> iOS, on the same EAR §742.15(b) route (publicly available source). Its
> precondition — the one-time notification email to BIS and the NSA, template in
> `app-store-listing.md` — still has to be sent once. One email covers both stores.

---

## Before the first upload — the things that cannot be undone later

1. **Account type decides the path.** A *personal* developer account created
   after 2023-11-13 cannot publish to production until the app has run a
   closed test with at least 12 opted-in testers for 14 consecutive days.
   **Ours is an organisation account, which is exempt.** The first track is
   *internal testing* either way (up to 100 tester emails, no review, live in
   minutes).
2. **Play App Signing — hand Play our key before the first bundle goes up.**
   Play's default generates its own app signing key. Then the Play build and
   the sideloaded APK carry different signatures, neither can update the
   other, and App Links (`https://proofkit.org/add`) stop verifying for Play
   installs until Google's certificate is added to
   `proofkit-dvpn/frontend/.well-known/assetlinks.json`. We want one key on
   both channels, so Play gets a copy of ours:
   - Actions → **Play App Signing key export** → *Run workflow*
     (`.github/workflows/play-signing-key.yml`). It decodes the
     `ANDROID_RELEASE_*` secrets — the `.jks` exists nowhere else — runs
     Google's `pepk` against them, and keeps `play-signing-key.zip` as a
     one-day artifact. The zip holds the private key encrypted to Google's
     published P-256 key; nothing but Google can open it. The job summary
     prints the certificate: `CN=ProofKit, O=Globvent inc`, SHA-256
     `59:DC:54:21:81:20:4C:88:40:2D:6C:71:EA:47:4B:8B:F0:9A:CA:B8:64:C9:00:DC:28:32:E6:FF:62:FC:3E:83`,
     the fingerprint already in `assetlinks.json`.
   - Download the artifact (GitHub wraps it in another zip; the file to
     upload is the inner `play-signing-key.zip`), then **delete the artifact
     from the run page** (the bin icon next to it). The repository is public,
     so anyone signed in to GitHub can fetch a workflow artifact; this one is
     ciphertext for Google's key and decryptable by nobody else, but a copy
     lying around is a copy someone could try to register as their own app's
     key. Retention is one day regardless.
   - Play Console → Setup → **App integrity** → App signing → *Use a
     different key* → *Export and upload a key from Java keystore* → upload
     it. The certificate the page then shows must match the summary above.
   - That page also offers a separate **upload key**. Skip it: the upload key
     is then the app signing key itself, which is what the release workflow
     signs with anyway.
3. **Upload the AAB, not an APK, and the `play` one.** Every release carries
   `Ghostlane-<version>-android-play.aab`. Checked on 1.0.397: `targetSdk = 37`
   (Play requires ≥ 36 for new apps since 2026-08-31); every arm64-v8a and
   x86_64 `.so` is 16 KB-page aligned (`LOAD` align `0x4000`).
   `armeabi-v7a/libgojni.so` is 4 KB-aligned, which is fine — 32-bit is
   outside the 16 KB requirement.
4. **The first bundle goes through the console by hand.** Google registers an
   app only through the console, so the Developer API — and the CI upload
   below — works from the second bundle on.

---

## Two channels, one app — the `play` flavor

The GitHub build is right for sideloading and wrong for Play, in two places
that Play's automated review reads straight from the manifest before a human
looks:

- **`REQUEST_INSTALL_PACKAGES` + the in-app updater.** `AndroidUpdateInstaller`
  downloads the next APK from GitHub Releases and hands it to the package
  installer. Play's Device and Network Abuse policy: an app distributed via
  Play may not update itself by any method other than Play's. And the
  permission's permitted uses (browsers, file managers, messaging with
  attachments, backup, device migration, enterprise management) do not include
  a VPN client, so the declaration form would be refused.
- **`QUERY_ALL_PACKAGES`.** Permitted only for device search, antivirus, file
  managers and browsers; everyone else fills the Permissions Declaration Form
  and is told no. The per-app routing list (`AndroidVpnManager.loadInstalledApps`)
  already asks for launcher apps through the `<queries>` element, which needs
  no permission; only its `getInstalledApplications` fallback shrinks without
  it.

So `androidApp` has a `store` flavor dimension: **`github`** (the APKs on the
releases page, unchanged) and **`play`**:

- `androidApp/src/play/AndroidManifest.xml` removes both permissions with
  `tools:node="remove"`.
- The `store_self_update` resource is `false`, so `AppActivity` builds no
  `AppUpdateService`; `AndroidMainScreen` then passes `showUpdates = false`
  and the Updates section is not drawn, the way the App Store build already
  behaves.
- `release.yml` builds `:androidApp:assembleGithubRelease` for the APKs and
  `:androidApp:bundlePlayRelease` → `dist/Ghostlane-<version>-android-play.aab`,
  and refuses to publish a play bundle whose manifest still names either
  permission (the bundle's manifest is protobuf, but permission names are
  plain strings in it).
- Same `applicationId`, version code and signing key on both flavors, so a
  phone can move from the APK to the Play build and back and still update.

Do not put a runtime check in place of the flavor: the manifest is judged at
upload, before any code runs.

**State 2026-09-12:** our key is Play's app signing key (uploaded via the
export workflow, RSA-AES mode); 1.0.398 (398) went to internal testing by
hand from run 34667107374; 1.0.399 (399) was uploaded to the internal track
by the workflow itself (run 34670196501, `Uploaded … versionCode 399 to the
internal track`). From here every `android`, `mobile` or `all` run lands on
internal without anyone touching the console.

---

## Store listing

**App name** (30 max) — [8]

```
Ghostlane
```

**Short description** (80 max) — [78]

```
A tunnel inside a video call. olcRTC, Reality, Hysteria2 and XHTTP in one app.
```

> The line shown in search results, and the one most people read. It leads with
> the thing only this app does. Play indexes the description for search (Apple
> does not), so the protocol names belong here rather than in a keyword field
> Play does not have.

**Full description** (4000 max) — [2640]

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
pick one.

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

Connecting routes the whole device through the exit you picked, using Android's
own VpnService. Apps you choose can stay outside the tunnel. The status screen
shows how long the session has been up and how much has gone through it.

MEASURING

Latency is measured where it can honestly be measured: through the tunnel for
the connection you are on, and by ICMP or a TCP connect for a server you are not
connected to. Where neither is possible the app says so instead of showing a
number it guessed.

PRIVACY

Nothing is collected. No account, no analytics, no advertising identifier, no
crash reporting service. Your server lists and the app's own log stay on the
device and are never sent anywhere.

The app talks to exactly two kinds of address: the server-list URL you added,
and the VPN servers in it. The one exception is a partner link that has to be
resolved into a server-list URL, and that request carries the link and nothing
about you.

REQUIREMENTS

A server configuration from a VPN provider: a server-list URL, a QR code, or a
pasted link. Ghostlane does not sell one and does not include one. Any provider
that speaks the protocols above will work.

OPEN SOURCE

github.com/romanpodpriatov/olcbox
```

> Two things stay out of this text. **No purchase pointer** ("the app can open
> proofkit.org, where you can get a subscription"): on Play that is steering
> users to a payment outside Google Play Billing for a service consumed in the
> app — the Payments-policy twin of the Apple 3.1.1 rejection this text already
> went through. And **no "subscription"** for the server-list URL: the 3.1.1
> rename made every user-visible string say "server list", and the listing has
> to agree with the screenshots.

**Category** — Tools. **Tags** — VPN, Privacy.

> Not "Communication" and not "Travel & Local"; both invite a category-mismatch
> review.

**Contact details** — support email; website
`https://github.com/romanpodpriatov/ghostlane`; privacy policy
`https://github.com/romanpodpriatov/ghostlane/blob/main/docs/privacy.md` (App content →
Privacy policy, by hand: the API does not carry it). Changed 2026-09-18 with the
rename, for the same reason as on the App Store: the client's listing describes
the client. `.github/workflows/play-listing.yml` pushes name, descriptions, icon and
feature graphic from this document with the release's service account.

> `proofkit.org/privacy` 301s to that slug now. Use the real one anyway: the
> site is a single-page app and answers 200 with the landing page to unknown
> paths, so a wrong privacy URL does not look wrong, it looks like marketing.

---

## Graphics — `docs/play/`

| Asset | File | Play's rule, and what was done |
|---|---|---|
| App icon | `icon-512.png` | 512 × 512 PNG, ≤ 1 MB. `androidApp/src/main/res/playstore_icon.png` flattened over the app background `#07080D` — the source has an alpha channel, and Play paints transparency black under its own mask |
| Feature graphic | `feature-graphic.png` | 1024 × 500, mandatory. Drawn from the app's own tokens and bundled fonts (Space Grotesk, IBM Plex); content inside a 72 px margin because some placements crop the edges. Regenerate with `scripts/play-assets.py` if the copy changes |
| Phone screenshots | `screenshots/0[0-5]-*.png` | 2–8 files, 320–3840 px on a side, **longest side at most twice the shortest**. The App Store set (`docs/screenshots/`, 1320 × 2868, 2.17:1) is refused by the uploader; these are the same captures with the iOS status bar (top 168 px) and home indicator (bottom 68 px) cropped off → 1320 × 2632, 1.99:1 |

> The captures are from an iPhone. The UI is the same Compose code on both
> platforms, but an Android capture (`adb exec-out screencap -p > x.png`) is
> better when a device is at hand; keep the same six states. Upload order:
> `03-connected`, `05-serverlistnotconnected`, `04-settings`, `02-vpnpopup` (the
> disclosure — review wants to see it), `01-empty`, `00-intro`.

---

## Data safety

- **Does your app collect or share any of the required user data types?** → **No**

That ends the section. It is consistent with `PrivacyInfo.xcprivacy`, with the
App Store answers, and with what the app does.

> The two network calls the app makes are worth understanding rather than
> guessing at. Fetching a server list sends its URL to the provider that issued
> it; resolving a partner link sends that link to proofkit.org. Neither carries a
> user identifier, and neither is one of Play's data types. The tunnel itself
> carries the user's traffic to the server they picked and is ephemeral
> processing, not collection. If analytics or crash reporting is ever added,
> this answer and the privacy manifest change in the same commit as the SDK.

**Data deletion** — nothing is collected, so there is nothing to request the
deletion of. Say so rather than leaving the URL field guessed at.

---

## App content

### VPN declaration — the one that actually decides this

- **Does your app use VpnService?** → **Yes**
- **Is VPN the app's core functionality?** → **Yes**
- **What data does the VPN service collect or transmit?** → None. The tunnel
  carries the user's traffic to the server in their own server list. The app
  does not read, record or transmit its contents, its destinations, or DNS
  queries.
- **Does the app redirect or manipulate other apps' traffic for monetisation?**
  → **No.** There is no advertising in the app, no ad injection, no ad
  replacement, and no routing decision made for any reason other than the exit
  the user selected.

> **Returned 2026-09-16 as "Missing or Incomplete Declaration"** — "the information
> you provided in the console declaration was insufficient". Not a policy finding;
> the form had empty or one-word fields. Every field below gets a full answer,
> pasted as written. Policy → App content → VPN service, then "Send changes for
> review" on the Publishing overview. No appeal: an appeal is for a decision we
> think is wrong, and this one is right.

#### Declaration form — every field

**Does your app use VpnService?** → Yes.

**Core functionality that requires VpnService** → VPN app. (Not an exception
category: the app is a VPN client and nothing else.)

**Explain how your app uses VpnService** (paste):

```
Ghostlane is a VPN client. VpnService is its core functionality and its only
purpose: the user imports a server list from their VPN provider (a link, a QR
code or a file), picks a server, and the app opens a device-level tunnel to that
server through android.net.VpnService. All of the device's traffic is routed
into the tunnel and out through the selected server; in the split-tunnel
setting the user can choose apps that stay outside the tunnel. The tunnel runs
only while the user has connected it and stops when the user disconnects, when
the server list entry is removed, or when Android revokes the VPN.

Before the first connection the app shows its own full-screen prominent
disclosure ("How the VPN connection works"), separate from any other consent,
explaining that all traffic will go through the selected server and to no one
else; the user must accept it before Android's VPN permission dialog appears.
Declining leaves the app usable without a tunnel.

The app does not inspect, record, redirect or modify the traffic it carries,
does not resolve or log DNS queries for its own use, has no advertising and no
ad SDK, and makes no routing decision other than the exit the user selected.
The Play listing documents the use of VpnService in the description ("routes
the whole device through the exit you picked, using Android's own VpnService").
```

**Does your app encrypt the data from the device to the VPN tunnel endpoint?** →
Yes. Every transport the app connects with runs inside an encrypted channel:
VLESS with Reality and VLESS over TLS use TLS 1.3; XHTTP runs over TLS; Hysteria2
runs over QUIC with TLS 1.3; olcRTC carries the tunnel inside a WebRTC session
protected by DTLS-SRTP, with an additional authenticated cipher on the tunnel
stream itself. The engines are sing-box, Xray-core and our own olcRTC engine
(github.com/romanpodpriatov/olcrtc), all open source.

**Does your app collect or transmit personal and sensitive user data?** → No.
No account, no sign-in, no analytics, no advertising identifier, no crash
reporting. The tunnel carries the user's traffic to the server they selected and
nothing is retained. The prominent disclosure is shown anyway, before the first
connection.

**Does your app redirect or manipulate user traffic from other apps for
monetization?** → No. No ads, no ad injection or replacement, no traffic sold or
routed for any purpose other than the exit the user selected.

**Where the Play listing documents the VpnService use** → the store description,
"BRINGING YOUR OWN SERVERS" paragraph: "Connecting routes the whole device
through the exit you picked, using Android's own VpnService. Apps you choose can
stay outside the tunnel."

**Privacy policy URL** → `https://github.com/romanpodpriatov/ghostlane/blob/main/docs/privacy.md`
(the same page the App Store listing uses; the Play listing's privacy URL field
must say the same).

**Videos** (if the form asks): the two unlisted YouTube links below, recorded on
a phone, 90 seconds each.

Two videos, 90 seconds each, unlisted on YouTube — **not private**, which a
reviewer cannot open:

1. Opening the app and using the VPN.
2. The prominent disclosure: the ordinary path to the screen, the whole text
   scrolled slowly enough to read, **declining** ("Not now") and what happens
   after it, and the screen being reached again. The decline path is the half
   that gets left out and the half that gets declarations returned.

> The disclosure is `VpnDisclosureScreen` in `commonMain` ("How the VPN
> connection works"), shown before the first connection and before Android's own
> VPN prompt. It is its own screen and combined with no other consent, which the
> policy requires explicitly. `02-vpnpopup.png` is it.

### App access

**Some functionality is restricted** — the app cannot be tested without a server
list, and a reviewer who cannot connect cannot review.

```
Ghostlane is a client for a server list the user already has. It does not sell or
include one, so it cannot be tested without a server-list link.

A working test link is below. In the app: tap + (top right) → "Paste link or
URI" → paste the link. The servers appear under Rooms. Tap any server, then the
button at the bottom of the screen ("CONNECT VIA …", or "TAKE A SEAT IN …" for an
olcRTC room). Before the first connection the app shows its own disclosure ("How the VPN
connection works" → I UNDERSTAND), then Android's VPN permission dialog.

Server-list link:
  <PASTE A LIVE LINK HERE>

The link carries several servers over different protocols; any of them connects.
Traffic is routed through Android's VpnService.

The app collects no data. There is no account and no sign-in. The camera is used
only to scan a QR code, and only when the user taps that button.
```

> The link must be live on the day of review and for some days after. A list
> that expires mid-review fails it.

### The rest of App content

- **Ads** — the app contains no ads.
- **Content rating** — every question "No". The result is Everyone / PEGI 3.
  There is an argument for a higher rating on unrestricted internet access; the
  app has no browser and no content of its own, so this is defensible, and the
  rating can be changed without a new build.
- **Target audience** — 18+. Not because of content, but because a VPN client is
  not a product for children and a lower bracket brings Families policy
  requirements with it.
- **News app** → No. **Government app** → No. **Financial features** → None:
  payment happens entirely outside the app, so there is no in-app purchase and
  none of the external-billing programmes apply. **Health** → None.

---

## Release path

1. **Internal testing, by hand once** — upload
   `Ghostlane-<version>-android-play.aab` from a release, add tester emails,
   share the opt-in link. No review. Verify on a real device installed *from
   Play*: connect works, the App Link `https://proofkit.org/add#…` opens the
   app, Settings has no Updates section.
2. **Then from CI, like TestFlight.** The Android job uploads the play bundle
   to a track whenever the `PLAY_SERVICE_ACCOUNT_JSON` secret is set
   (`scripts/play-upload.py`, Google Play Developer API). To set it up:
   - Google Cloud Console → IAM → *Service accounts* → create one (any
     project; the name is for us) → *Keys* → add a JSON key.
   - Play Console → *Users and permissions* → *Invite new users* → the
     service account's email → *App permissions* → Ghostlane → tick **Release
     to testing tracks** and **View app information**; add **Release to
     production** only if the `production` choice below is wanted.
   - GitHub → Settings → Secrets → Actions → `PLAY_SERVICE_ACCOUNT_JSON` =
     the whole JSON file.
   Every `android`, `mobile` or `all` run then lands on Play's internal
   track within minutes of the build. The `play_track` input goes to
   `production` for a direct release (Play reviews it first) or `none` to
   build only; promoting a tested internal build from the console is the
   safer production path, because it ships the exact bundle testers had.
3. **Production** — complete every App content declaration first; the first
   production review of a VPN app takes days, not hours.
4. **Keep publishing the APKs.** Clients for circumventing blocks do get pulled
   from stores, and the day that happens is the wrong day to find the other
   channel had been dropped.
