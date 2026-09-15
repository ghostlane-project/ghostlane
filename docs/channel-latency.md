# Channel latency and subscription refresh

The active channel is sampled with an HTTPS 204 request every 30 seconds while
the home screen is resumed. This is HTTP response time, not ICMP RTT, bandwidth,
or proof that every destination works. Telemost uses its existing session; no
extra room is joined. The UI distinguishes an unfinished first sample (`HTTP …`)
from a completed failure (`HTTP —`). A failed sample does not restart the VPN.

Each platform owns one HTTP client per connection, closing it on stop or network
migration. Requests share a bounded, serialized probe with an overall deadline.
Android uses the configured SOCKS connect host and the running core's port and
credentials. Desktop uses the verified proxy chain; iOS uses the existing packet
tunnel. Session identity and the view model's migration epoch reject late results.

Other Android/desktop entries retain their existing address checks while the VPN
is connected. These are not credential or end-to-end tunnel checks. Manual row
measurements survive network changes and are marked as previous-network results
until remeasured. Loading no longer clears the column or cancels measure-on-start. The live
channel display is separate and resets when the connection is interrupted.

Automatic refresh waits for persisted settings and checks due subscriptions at
startup and every five minutes while the app runs. It honors the shorter of the
provider interval and the user's maximum interval. Failed downloads preserve the
previous list. Cancellation is propagated. No iOS background execution guarantee
is made.

This change preserves the existing choice of VPN core and emits no group,
balancer, observatory or additional SOCKS inbounds. Selection belongs to a
separate change. `ChannelMeasurement` only distinguishes pending from a completed
failure; `ChannelLatency.Session` owns the reusable client. Neither adds storage
or a runtime dependency.

Local Windows validation: JVM/common tests and Desktop Kotlin compilation
passed after the review fixes. Coverage includes a late response after migration
with an unchanged session clock, pending/failure display, HTTP client reuse,
cancellation, deadlines and subscription refresh policy. Platform CI results are
recorded in the PR. Apple Kotlin compilation is not a signed app/device test;
Wi-Fi/LTE migration, real Telemost traffic and extension memory still require a
physical iPhone run before release.
