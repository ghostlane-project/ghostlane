# Bypass: the sniff no longer costs a connection its first 300 ms

Follow-up to `2026-09-15-olcrtc-client-routing-design.md`, which introduced the
sniff. Ghostlane issue #35.

## Problem

Under Bypass, a CONNECT whose target is an address the rules do not cover is
answered and then read for up to `sniffTimeout` (300 ms), because a TLS or HTTP
client names its destination in its first bytes and that name may be one the
rules send direct.

A protocol where the server speaks first sends nothing, so it waits out the
whole window before anything is dialed: SSH, SMTP, IMAP, POP3, MySQL,
PostgreSQL, Redis, and any protocol on a port nobody standardised. It is not
an edge case. Under hev the SOCKS target is usually an address rather than a
name, so this is the ordinary path for every one of those connections.

## Goal

A silent client loses nothing to the sniff, and a client whose first bytes name
a direct destination still goes direct.

## Decision

Race the two, rather than a port list or a shorter window.

- A **port list** (22, 25, 143, 3306, …) is a guess that is right for the ports
  on it and wrong everywhere else; a server-speaks-first protocol on a port
  nobody standardised keeps paying.
- A **shorter window** does not remove the wait, and it trades it for a worse
  failure: a TLS hello that misses a 50 ms window sends a site the rules cover
  through the tunnel, which is Bypass silently not working.
- The **race** costs nothing in either direction and is bounded: the only
  connections that pay for it are the ones the sniff sends direct, and those
  are exactly the CDN case — a name the rules cover on an address they do not.

## Design

`sniffThenRoute` today: reply, `sniffHead`, then direct or tunnel.

It becomes: reply, then start both

1. the sniff, as now; and
2. the tunnel's own preparation — wait for the session, open the stream, send
   the CONNECT and read the exit's ack — in a goroutine, up to the point where
   bytes would flow. Nothing is written to the client and nothing is read from
   it on this path; the sniff owns the client's first bytes.

When the sniff answers:

- **a name the rules cover** → the prepared stream is closed (whenever it
  arrives; the goroutine hands it over and the loser closes it) and the
  connection is dialed direct by that name, as now;
- **anything else** → the prepared tunnel is taken. It is already open, so the
  connection starts the moment the sniff gives up instead of a round trip
  later.

A preparation that failed is reported exactly as today's `tunnelWhenReady`
would have: the same reply codes, the same parking behaviour while the session
is down, the same failure when it never comes up.

The exit dials the target for a connection the sniff then sends direct. That is
one wasted dial on the CDN path, closed immediately, and it is the whole price
of the change.

## Testing

In `internal/client/direct_test.go`, beside `TestSilentClientTakesTheTunnel`:

- a silent client is connected to the tunnel in well under `sniffTimeout`
  (the hook already exists) — this is the regression the issue is about;
- a client whose hello names a direct rule still goes direct, and the stream
  the race opened is closed rather than left to the session;
- a tunnel preparation that fails while the client stays silent fails the
  CONNECT the way it does today.

## Rollout

Engine change only, behind no flag: with no rules (`Bypass` off) the sniff path
is not reached at all. It ships with the next engine pin and the gate covers
the transports it rides on.
