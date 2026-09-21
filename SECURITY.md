# Security policy

Ghostlane carries other people's traffic. A flaw here can expose what someone
went out of their way to protect, so security reports are treated as the most
important work in the queue.

## Reporting a vulnerability

**Please do not open a public issue.**

Use GitHub's private vulnerability reporting on the repository the flaw is in:

- application: <https://github.com/ghostlane-project/ghostlane/security/advisories/new>
- engine: <https://github.com/ghostlane-project/olcrtc/security/advisories/new>

That channel is private between you and the maintainers, supports attachments,
and turns into a published advisory with credit once a fix ships.

If you cannot use it, say so in an issue with no technical detail at all, and a
maintainer will arrange another way to receive the report.

## What to include

Whatever you have. What helps most: the version and platform, what an attacker
has to be able to do first, what they get, and the smallest way to reproduce it.
A proof of concept is welcome but never required.

## What to expect

- An acknowledgement within three days. If you hear nothing in a week, assume
  the message went missing and ping the maintainer publicly without details.
- An assessment within two weeks: whether we agree it is a vulnerability, how
  serious we think it is, and what we intend to do.
- A fix in a release, and a published advisory crediting you unless you would
  rather stay anonymous.

We do not pay bounties today. If the project is funded well enough to, it will
be said here first.

## Scope

In scope: the application and the engine in these two repositories, their
release and signing pipelines, and anything either ships to a user.

Out of scope, though still worth telling us about: the meeting services and
relay operators the engine speaks to, a server list provider's own service, and
the platforms the app is distributed on. We can only fix what we ship.

## Disclosure

We ask for the ordinary courtesy of time to fix before publication, and we do
not ask for silence. Ninety days is our default; if a fix will take longer we
will say so and why, and if it takes us less we will publish sooner. A report
already being exploited in the wild changes the timetable, not the courtesy.

## Known limits, stated plainly

This software hides the shape of a tunnel. It does not make its user anonymous,
it cannot protect someone from an adversary who controls their device, and a
meeting service can always see that a call happened. Those are properties of the
design, not vulnerabilities in it. A report showing that one of them is worse in
practice than stated is very welcome.
