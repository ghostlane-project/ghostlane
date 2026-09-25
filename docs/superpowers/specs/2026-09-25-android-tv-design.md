# Android TV (roadmap item 12)

The phone app, installable and usable on Android TV. The case that matters: a
television in Russia watching YouTube, which is slowed down there, through the
tunnel — "only blocked sites through the tunnel" (item 10) sends exactly that and
nothing else.

## What changes

1. **Launcher and store eligibility.** `android.software.leanback` and
   `android.hardware.touchscreen` declared optional (the phone build stays one
   APK), a `LEANBACK_LAUNCHER` entry on the main activity, and a 320×180 banner.
2. **Focus you can see.** A TV is driven by a D-pad: every control already takes
   focus (Compose `clickable`), but the default indication is a faint overlay on a
   dark theme. On a TV the root provides an `Indication` that draws a clear accent
   ring and a light fill on the focused control. Phones keep their ripple.
3. **A server list without a camera or a clipboard.** The add sheet gets *From
   your phone* where the platform offers it (a TV, or any device without a camera).
   It opens a sheet with a QR code and an address: a one-page web form the device
   serves on the home network. The phone scans the code, pastes the server-list
   link into the page and sends it; the device imports it as a paste would.
4. **No camera, no scanner.** *Scan QR code* is offered only where there is a
   camera.

## The form server (`PhoneImportServer`, jvmAndroidMain)

- Listens only while the sheet is open, on the device's site-local IPv4 address
  (Wi-Fi or Ethernet, never a tunnel interface), on a port the kernel picks.
- The path is a 128-bit random token; anything else is 404. The token is the
  secret: it is in the QR code and on the TV screen, and nowhere else.
- `GET /<token>` serves the form; `POST /<token>` takes
  `application/x-www-form-urlencoded` `link=…`, at most 16 KiB, and hands it to the
  import. Requests are read with a 5 s timeout and at most 8 KiB of headers; one
  connection at a time; `Connection: close`.
- The page is plain HTML with no script, in the device's language (the strings
  are the app's own resources), and reflects nothing it was sent.
- No device on the network: the sheet says to join one instead of showing a code.

## Not in this step

TV screenshots and the Play Console "Android TV" opt-in are the owner's (store
listing). An emulator or a TV is needed to judge D-pad paths end to end; the code
is checked by unit tests (the server over a real socket) and by rendering the new
sheet and the focus ring off-screen.
