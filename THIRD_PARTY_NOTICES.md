# Third-party notices

Ghostlane itself is MIT (`LICENSE`). It ships and links components written by
other people, each under its own licence. This file lists them, because a
licence that travels with the binary has to be findable, and because two of
these carry obligations the MIT licence does not.

## What ships inside the applications

| Component | Licence | How it is used |
| --- | --- | --- |
| [olcRTC](https://github.com/ghostlane-project/olcrtc) | Apache-2.0 | The tunnel engine. Linked into every platform; maintained by this project. |
| [sing-box](https://github.com/SagerNet/sing-box) | **GPL-3.0-or-later** | Reality, TLS and Hysteria2. A separate binary on desktop; its `libbox` library is linked into the mobile builds. |
| [Xray-core](https://github.com/XTLS/Xray-core) | **MPL-2.0** | XHTTP, through [libXray](https://github.com/XTLS/libXray) (MIT). A separate binary on desktop; linked into the mobile builds. |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | MIT | Turns the platform's TUN device into SOCKS5 on Apple platforms. |
| [Wintun](https://www.wintun.net/) | GPL-2.0 (redistribution permitted per its own terms) | The Windows TUN driver. Shipped as the vendor's signed binary, unmodified. |
| [Re:filter](https://github.com/1andrevich/Re-filter-lists) | MIT, Copyright (c) 2024 Andrevich | The list of sites blocked in Russia behind "only blocked sites through the tunnel": its sing-box rule-sets, bundled unmodified (`files/rules/refilter-*.srs`, pinned in `scripts/rule-sets.lock`). |

Their sources are the upstream repositories above, at the versions pinned in
`scripts/cores-pins.sh` and `scripts/hev-pins.sh`. Nothing in this repository
modifies sing-box; the one patch carried against Xray-core is in
`scripts/patches/` and is therefore published, as MPL-2.0 requires.

## What this means in practice

**sing-box is GPL-3.0-or-later.** Linking it into an application makes the
combined work subject to that licence when the combination is distributed. This
project's own source is public, which satisfies the source-availability side of
it. The known friction is distribution through app stores, whose terms have been
argued to conflict with the GPL's condition that no further restrictions be
imposed on recipients; this is the same question every sing-box-based client on
those stores faces. We are not lawyers, we state the facts here rather than a
conclusion, and we will follow whatever SagerNet and the stores settle on.

**Xray-core is MPL-2.0**, which is file-level copyleft: modified MPL files must
be published. Ours is, in `scripts/patches/`.

**Everything else is permissive** and needs only the attribution given above.

If you build a product on top of this repository, these obligations travel with
the components, not with Ghostlane's MIT licence. Read them before you ship.
