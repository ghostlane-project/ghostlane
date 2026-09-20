# Support

## Something is broken

Open an issue: <https://github.com/romanpodpriatov/ghostlane/issues>

What makes a report useful, roughly in order of how much it helps:

- the app version, from the bottom of the main screen, and the platform;
- what you did, what you expected, and what happened instead;
- the protocol and the provider in use (olcRTC through which meeting service,
  or VLESS, Hysteria2, XHTTP);
- whether it worked before, and on which version;
- the diagnostics the app can export, with anything private removed.

Please do not put a server list, a subscription URL or its token in a public
issue. They are credentials. Describe the entry instead, or redact the link.

## Something is wrong in the engine

The tunnel itself lives in <https://github.com/romanpodpriatov/olcrtc/issues>.
If you are not sure which side a problem is on, file it against the app and it
will be moved.

## A security problem

Do not open an issue. [SECURITY.md](SECURITY.md) says how to report it.

## A question

Questions are welcome as issues. There is no forum or chat; this keeps answers
searchable for the next person with the same question.
