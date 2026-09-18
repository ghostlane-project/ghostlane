#!/usr/bin/env python3
"""Push the store listing to Google Play: name, descriptions, icon, feature graphic.

The text comes from docs/play-listing.md — the code block under each of
**App name**, **Short description** and **Full description** — so the document
the user reads and the store agree by construction. Images come from docs/play/,
which scripts/play-assets.py renders from the app's own icon and fonts.

What the API will not do, by Google's design: the privacy policy URL, the App
content declarations and the category live in the console and stay a human's
job. Screenshots are deliberately not touched here either — they show the app,
so they are captured on a phone, not rendered.

Needs google-api-python-client and google-auth, and a service account with
"Manage store presence" on the app (the upload-only role is not enough).
"""
import argparse
import json
import re
import sys

import google_auth_httplib2
import httplib2
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

SCOPE = "https://www.googleapis.com/auth/androidpublisher"
LANGUAGE = "en-US"
LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}


def block_after(markdown: str, heading: str) -> str:
    """The first fenced code block after `**heading**`, without the fences."""
    start = markdown.find(f"**{heading}**")
    if start < 0:
        raise SystemExit(f"docs/play-listing.md: no **{heading}** section")
    match = re.search(r"```\n(.*?)\n```", markdown[start:], re.S)
    if not match:
        raise SystemExit(f"docs/play-listing.md: no code block under **{heading}**")
    return match.group(1).strip("\n")


def listing_from(path: str) -> dict:
    text = open(path, encoding="utf-8").read()
    listing = {
        "language": LANGUAGE,
        "title": block_after(text, "App name"),
        "shortDescription": block_after(text, "Short description"),
        "fullDescription": block_after(text, "Full description"),
    }
    for field, limit in LIMITS.items():
        if len(listing[field]) > limit:
            raise SystemExit(f"{field} is {len(listing[field])} characters, the limit is {limit}")
    return listing


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
    parser.add_argument("--listing", default="docs/play-listing.md", help="the document to read the copy from")
    parser.add_argument("--icon", default="docs/play/icon-512.png", help="512x512 PNG, no alpha")
    parser.add_argument("--feature-graphic", default="docs/play/feature-graphic.png", help="1024x500 PNG")
    parser.add_argument("--website", help="contact website for the listing (Store listing → Contact details)")
    parser.add_argument("--dry-run", action="store_true", help="print what would change and commit nothing")
    args = parser.parse_args()

    listing = listing_from(args.listing)
    print(f"title: {listing['title']!r}")
    print(f"short: {listing['shortDescription']!r}")
    print(f"full:  {len(listing['fullDescription'])} characters, starts {listing['fullDescription'][:48]!r}")
    print(f"icon: {args.icon}; feature graphic: {args.feature_graphic}; website: {args.website or '(unchanged)'}")
    if args.dry_run:
        print("dry run: nothing sent")
        return 0

    credentials = service_account.Credentials.from_service_account_file(args.service_account, scopes=[SCOPE])
    transport = httplib2.Http(timeout=120)
    transport.redirect_codes = transport.redirect_codes - {308}
    authorized = google_auth_httplib2.AuthorizedHttp(credentials, http=transport)
    play = build("androidpublisher", "v3", http=authorized, cache_discovery=False)
    edits = play.edits()

    try:
        edit_id = edits.insert(packageName=args.package, body={}).execute(num_retries=3)["id"]
        edits.listings().update(
            packageName=args.package, editId=edit_id, language=LANGUAGE, body=listing
        ).execute(num_retries=3)
        print("listing text updated")

        for image_type, path in (("icon", args.icon), ("featureGraphic", args.feature_graphic)):
            edits.images().deleteall(
                packageName=args.package, editId=edit_id, language=LANGUAGE, imageType=image_type
            ).execute(num_retries=3)
            edits.images().upload(
                packageName=args.package,
                editId=edit_id,
                language=LANGUAGE,
                imageType=image_type,
                media_body=MediaFileUpload(path, mimetype="image/png"),
            ).execute(num_retries=3)
            print(f"{image_type} replaced from {path}")

        if args.website:
            details = edits.details().get(packageName=args.package, editId=edit_id).execute(num_retries=3)
            details["contactWebsite"] = args.website
            edits.details().update(packageName=args.package, editId=edit_id, body=details).execute(num_retries=3)
            print(f"contact website set to {args.website}")

        edits.commit(packageName=args.package, editId=edit_id).execute(num_retries=3)
        print("edit committed; the console shows the new listing (review may apply before it goes live)")
    except HttpError as error:
        print(f"Google Play refused: {describe(error)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
