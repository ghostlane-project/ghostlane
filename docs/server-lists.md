# Server lists

How a server list gets into Ghostlane, the one-tap link forms, and the HTTP headers a provider can send with a list. [Back to the README](../README.MD).

You bring the list; the app turns it into servers. Paste a link, scan a QR code,
or open a file. A provider's panel or bot can also hand over a one-tap link:

```text
ghostlane://add?url=<payload>
ghostlane://add/<server-list URL>
https://proofkit.org/add#<payload>
```

The path form is the one provider pages write for every client, the URL raw or
percent-encoded. `proofkit://`, the scheme the app registered under its former
name, still works the same way. It is kept because links already handed out by
providers, and copies already installed, use it; dropping it would break them
for no gain. The web spelling is
for the places that will not open a custom scheme: a phone with the app
installed opens it in the app, a phone without it lands on a page with the
downloads. There the payload rides the fragment on purpose: a fragment
never leaves the browser, so the list, which is a credential, is in nobody's
access log. For the same reason the app shows where a list came from and never
reads the link itself back out.

## What a provider can say in the headers

A provider can say more in the list's own HTTP headers, in the forms other
clients read:
- `profile-title`;
- `subscription-userinfo` (traffic and expiry);
- `support-url` and `profile-web-page-url`;
- `profile-update-interval`;
- `announce`, a short note shown under the list on Android and the desktop.

## A list whose address is blocked

Two headers keep a list alive when its address is blocked:
- `fallback-url` names a spare address, which the app asks when the usual one
  does not answer;
- `new-url` or `new-domain` moves the list for good.

Both are honoured only from an HTTPS answer and only to an HTTPS address: over
plain HTTP anyone on the path could write them, and a moved list never comes
back by itself.
