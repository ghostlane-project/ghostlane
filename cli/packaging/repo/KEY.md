# Repository signing key

The apt and dnf repositories on GitHub Pages are signed with one GPG key,
`ghostlane-repo.gpg.asc` (RSA 4096, sign-only, 2-year expiry, made 2026-10-01):

    fingerprint 862C 1829 3437 3928 03AD  BF47 E168 6027 7F51 A4DE
    uid         Ghostlane CLI repository <ghostlane-project@users.noreply.github.com>

The private key is the repository secret `CLI_REPO_GPG_KEY` (armored); the
release workflow imports it into a throwaway GNUPGHOME for `reprepro` and
`rpmsign`. It is a different key from the release signing key
(`cli-release.pub.pem`, ed25519) that signs SHA256SUMS for `install.sh`.

Rotation or expiry: generate a new key, put its public half here, set the
secret, run a release. A dnf client re-imports the key from `gpgkey=` (it
asks once); an apt client verifies against the keyring file it downloaded
(`/etc/apt/keyrings/ghostlane.gpg`) and must download it again — say so in
the release notes.
