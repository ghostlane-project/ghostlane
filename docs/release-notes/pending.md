### Reality lines open on current Xray servers

A Google Play review said ordinary VLESS servers did not work while olcRTC did. It was wider than Android: Xray servers released since July 2026 refused every Reality line the app runs, on every platform. An Xray v26.7 server refuses a client that announces a version below 26.3.27, and sing-box announces 1.8.1. From v26.9.8, a hello without a post-quantum key share (X25519MLKEM768) ahead of X25519 is handed to the camouflage site instead, and sing-box removed that share from every Reality hello.

The app's sing-box is now built from its pinned release with a patch, the same tree on every platform. The patch:
- keeps the key share;
- sends Chrome's hello for a fingerprint whose hello has none;
- announces 26.7.11.

Android and the desktops move from Xray 25.3.6 to 26.7.11, the version iOS and the CLI already run. Reality lines open on Xray v26.4.25, v26.7.11 and v26.11.9. The tests now fetch a page through the tunnel from each of these servers, and the same line with a wrong key must not reach it. ([#160](https://github.com/ghostlane-project/ghostlane/pull/160))

### A link written with Xray's names no longer stops the connection

Links name uTLS fingerprints and VLESS flows as Xray does: `hellochrome_133`, `randomizednoalpn`, `xtls-rprx-vision-udp443`. sing-box knows other names, and one name it did not know made it refuse the whole configuration. The app now reads Xray's names as their sing-box equivalents. ([#161](https://github.com/ghostlane-project/ghostlane/pull/161))
