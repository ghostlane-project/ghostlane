The allow list of scripts/check-no-go.sh (K1): one empty file per artefact that
may still hold Go today, named like the artefact with its version replaced by
VERSION (for example Ghostlane-VERSION-android-release.apk).

The list only shrinks. The commit that flips an artefact to the Rust core
deletes its file; an artefact that is clean while its file is still here fails
the check as STALE. Nothing is added to this directory once the first baseline
is in: a new artefact that holds Go is a failure, not an entry.
