#!/usr/bin/env python3
"""Upload an Android App Bundle to a Google Play track.

The Android half of what the iOS job does with TestFlight: one call from CI,
authenticated as a service account the Play Console was told about, and the
build is on the track a minute later. Two things the API will not do, by
Google's design, and this script therefore expects to have happened already:
registering the app (the first bundle of a new app goes through the console by
hand) and choosing the signing key (App integrity -> App signing, before that
first bundle).

Needs google-api-python-client and google-auth. The service account needs
"Release to testing tracks" on the app for `internal`, and "Release to
production" for `production`.
"""

import argparse
import json
import sys

import google_auth_httplib2
import httplib2
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

SCOPE = "https://www.googleapis.com/auth/androidpublisher"
# A multiple of 256 KiB, as the resumable protocol wants. 32 MiB was one
# request per chunk of a 200 MB bundle and one of them outran the 60 s
# default timeout on a runner's uplink, which ended the release with
# "TimeoutError: The read operation timed out"; 8 MiB keeps each request
# short enough that a slow minute is a slow chunk rather than a failure.
CHUNK = 8 * 1024 * 1024
# Long enough for a chunk on a bad uplink, short enough that a genuinely
# dead connection does not hold the job for the whole run.
HTTP_TIMEOUT_SEC = 300
# googleapiclient retries socket timeouts and 5xx with backoff, but only
# when asked: the default is zero and one flaky chunk fails the upload.
RETRIES = 5
# Play takes this many characters of "What's new" for a language and refuses
# a release whose text is longer.
NOTES_MAX = 500


def whats_new(notes, notes_file):
    """The text for "What's new": the file's when it is there and says
    something, else `notes`.

    The file is docs/release-notes/play-whats-new.txt, written for the release
    and emptied after it. Without it a release went to the track with its
    version number for a text, which is what a tester then read as "what's
    new". A text over Play's limit is cut at the last whole line that fits and
    said so, not sent: Play would refuse the release, and a refused upload
    fails the Android job, which holds back every other platform's publish.
    """
    text = notes or ""
    if notes_file:
        try:
            with open(notes_file, encoding="utf-8") as handle:
                from_file = handle.read().strip()
        except OSError:
            from_file = ""
        if from_file:
            text = from_file
    if len(text) > NOTES_MAX:
        print(
            f"What's new is {len(text)} characters and Play takes {NOTES_MAX}: cut at the last line that fits.",
            file=sys.stderr,
        )
        kept = text[:NOTES_MAX]
        text = kept[: kept.rfind("\n")] if "\n" in kept else kept
    return text


def describe(error: HttpError) -> str:
    try:
        body = json.loads(error.content.decode("utf-8"))
        return body.get("error", {}).get("message") or error.content.decode("utf-8")
    except (ValueError, AttributeError):
        return str(error)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--service-account", required=True, help="path to the service account JSON key")
    parser.add_argument("--package", required=True, help="applicationId, e.g. org.proofkit.app")
    parser.add_argument("--bundle", required=True, help="the .aab to upload")
    parser.add_argument("--track", default="internal", help="internal, alpha, beta, production, or a custom track name")
    parser.add_argument("--release-name", help="how the release is listed in the console (defaults to the version name)")
    parser.add_argument("--notes", help="What's new, en-US, when --notes-file has nothing to say")
    parser.add_argument("--notes-file", help="a file with What's new, en-US; missing or empty means --notes")
    parser.add_argument("--status", default="completed", choices=["completed", "draft", "inProgress", "halted"])
    args = parser.parse_args()

    credentials = service_account.Credentials.from_service_account_file(args.service_account, scopes=[SCOPE])
    transport = httplib2.Http(timeout=HTTP_TIMEOUT_SEC)
    # A resumable upload answers each chunk with 308 "Resume Incomplete",
    # which httplib2 treats as a permanent redirect and then rejects for
    # having no Location header. googleapiclient's own build_http() drops 308
    # from the redirect codes for exactly this reason; supplying our own
    # transport to raise the timeout means supplying that too.
    transport.redirect_codes = transport.redirect_codes - {308}
    authorized = google_auth_httplib2.AuthorizedHttp(credentials, http=transport)
    play = build("androidpublisher", "v3", http=authorized, cache_discovery=False)
    edits = play.edits()

    try:
        edit_id = edits.insert(packageName=args.package, body={}).execute(num_retries=RETRIES)["id"]

        media = MediaFileUpload(args.bundle, mimetype="application/octet-stream", resumable=True, chunksize=CHUNK)
        uploaded = (
            edits.bundles()
            .upload(packageName=args.package, editId=edit_id, media_body=media, ackBundleInstallationWarning=True)
            .execute(num_retries=RETRIES)
        )
        version_code = uploaded["versionCode"]

        release = {"versionCodes": [str(version_code)], "status": args.status}
        if args.release_name:
            release["name"] = args.release_name
        notes = whats_new(args.notes, args.notes_file)
        if notes:
            release["releaseNotes"] = [{"language": "en-US", "text": notes}]
        edits.tracks().update(
            packageName=args.package,
            editId=edit_id,
            track=args.track,
            body={"track": args.track, "releases": [release]},
        ).execute(num_retries=RETRIES)

        try:
            edits.commit(packageName=args.package, editId=edit_id).execute(num_retries=RETRIES)
        except HttpError as error:
            # Play refuses to send an edit for review by itself when the app has
            # other changes pending in the console. The upload is still wanted;
            # the review is then started from the console, as the message says.
            if "changesNotSentForReview" not in describe(error):
                raise
            edits.commit(packageName=args.package, editId=edit_id, changesNotSentForReview=True).execute(num_retries=RETRIES)
            print("Committed without sending for review: the console has other pending changes.")
    except HttpError as error:
        print(f"Google Play refused: {describe(error)}", file=sys.stderr)
        return 1

    print(f"Uploaded {args.bundle} as versionCode {version_code} to the {args.track} track ({args.status}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
