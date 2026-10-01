# Repository signing key

The apt and dnf repositories on GitHub Pages are signed with one GPG key,
`ghostlane-repo.gpg.asc` (RSA 4096, sign-only, 2-year expiry, made 2026-10-01):

    fingerprint 862C 1829 3437 3928 03AD  BF47 E168 6027 7F51 A4DE
    uid         Ghostlane CLI repository <ghostlane-project@users.noreply.github.com>

The private key is the repository secret `CLI_REPO_GPG_KEY` (armored); the
release workflow imports it into a throwaway GNUPGHOME for `reprepro` and
`rpmsign`. It is a different key from the release signing key
(`cli-release.pub.pem`, ed25519) that signs SHA256SUMS for `install.sh`.

Rotation or expiry: generate a new key, put its public half here (the apt
and dnf clients fetch `ghostlane-repo.gpg.asc` from the repo root, so an
already configured client picks the new key up on its next key refresh —
`apt-key`-less setups need the keyring file re-downloaded), set the secret,
run a release.
