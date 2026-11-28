# Store texts for the Rust switch (drafts)

These are drafts. Nothing here has been sent or pasted anywhere. The owner sends
each text himself (owner decision 6). Facts come from this repository:
`THIRD_PARTY_NOTICES.md`, `scripts/ghostlane-core-pin.sh`, `docs/rust-core.md`.

`scripts/p6-tests/store-drafts.sh` reads this file. It fails when a text names a
retired carrier, a meeting service or one of the old engines' libraries, or when the keywords pass
100 characters or the Play line passes 500.

## Export-compliance notice

Same route as before: publicly available source, EAR section 742.15(b). One
email covers both stores. Fill in the contact line, send it once, keep the sent
copy. Send it before the first store upload of a build with the Rust core.

To: `crypt@bis.doc.gov`, `enc@nsa.gov`
Subject: `Notification of publicly available encryption source code`

```
This is a notification under Section 742.15(b) of the Export Administration
Regulations of publicly available encryption source code.

Product:            Ghostlane - VPN client for iOS, Android, macOS, Windows
                    and Linux
Source code URL:    https://github.com/ghostlane-project/ghostlane

The complete corresponding source code is available at the URL above without
charge and without restriction on access. The app uses its own core, written
in Rust, and the source of the core is public:

  https://github.com/ghostlane-project/xray-rust
  https://github.com/ghostlane-project/olcrtc

Cryptographic functionality is provided by publicly available libraries:
BoringSSL and rustls (with ring and AWS-LC) for TLS and QUIC, and the RustCrypto
crates for AES-GCM and ChaCha20-Poly1305. The full list of components and
licences is in THIRD_PARTY_NOTICES.md in the repository.

The algorithms used are published standards: AES, ChaCha20-Poly1305, X25519,
SHA-2, HKDF and TLS. No cryptographic algorithm developed by us is included.

Submitter:          Globvent inc
Contact:            <name, email, telephone>
```

## Store keywords

App Store Connect, 100 characters at most, no spaces after commas. Do not repeat
the app name or the subtitle. No engine names and no names of other services.

```
olcrtc,webrtc,censorship,dpi,firewall,obfuscation,tunnel,relay,traffic,bypass,private
```

## Play "what's new" line

Google Play, 500 characters at most.

```
Ghostlane now runs on its own core, written in Rust. Connections use the same server links as before. Some older link shapes that the new core cannot carry are refused with a short message before the tunnel starts.
```
