### HTTP latency of the live channel

While connected, the home screen now shows the HTTP response time of the channel you are actually on, Telemost rooms included: one small request through the tunnel every 30 seconds while the app is in the foreground. It reads `HTTP …` until the first sample lands and `HTTP —` after a failed one; a failed sample never restarts the VPN. Other servers keep their usual address pings.

### Subscription refresh that keeps its word

Automatic refresh now waits for your saved settings before its first run, checks due lists at startup and every five minutes, follows the shorter of the provider's and your own interval, and keeps the current list when a download fails.

Nothing in the VPN cores, the pinned frameworks or the iOS packet extension changed in this release. Contributed by @igves96 (#29).
