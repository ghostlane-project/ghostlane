# VLESS connection groups and channel latency

In Subscription settings, Manual keeps the existing single-location behavior.
Lowest and Balanced use the VLESS entries from the selected location's subscription.
Standalone links and olcRTC rooms remain manual. Supported VLESS transports are
those the parser already supports: TCP and XHTTP, with their existing TLS/Reality settings.

One Xray process owns the group. Lowest uses the leastPing strategy; Balanced
randomly selects an observed available member for each new connection. Existing
connections retain their outbound. Balanced can use different exit IPs concurrently;
it does not combine bandwidth for a single connection. While the observatory warms
up, the selected member is the fallback. A failed group never falls back to direct internet.
The user's Bypass Russia routing remains a separate, explicit routing choice.

Xray probes members through their VLESS outbounds with an HTTPS 204 request every
minute. The UI's Measure action uses a separate loopback SOCKS inbound pinned to
that member, so a successful neighbor cannot make a failed member look healthy.
These HTTP measurements are available while the group is connected. Disconnected
address probes remain address probes; they do not prove the VPN credentials work.
Cached results are cleared when the connection session changes.

The connected status also shows HTTP response time, refreshed every 30 seconds
while the home screen's lifecycle is resumed. For Telemost this request goes through the
existing media session. It does not join another room or consume another seat.
An unanswered probe displays a dash and does not trigger a reconnect. The number
includes request setup and the destination's response time; it is neither ICMP RTT
nor a download speed test. Failure of the probe destination alone can affect it.
The UI requires an HTTP 204 response and rejects redirects. Xray's observatory
has its own health criteria; a latency result is not a guarantee that every
destination is reachable.

## Architecture proposal

This is an opt-in design for review. Manual retains the existing core selection:
sing-box for TCP VLESS and Hysteria2, Xray for XHTTP, and olcRTC for rooms.
Grouped TCP VLESS changes to the existing Xray path. That change is a tradeoff
requiring maintainer agreement, not just a UI addition.

The group uses the project's existing Xray runtime and JSON builder because they
can host TCP and XHTTP members together and provide both strategies in one core.
There is no new runtime dependency, Go fork, or process per member. Native
sing-box URLTest is a smaller alternative for TCP-only Lowest. It does not by
itself implement the same mixed-transport, per-connection Balanced behavior.
If retaining sing-box for every TCP connection is the desired boundary, split
group selection out and ship latency/refresh independently before redesigning it.

The added types have limited roles:

| Type | Why it exists | Existing infrastructure reused |
| --- | --- | --- |
| `ConnectionSelection` | Three persisted choices with Manual as default | `SubscriptionSettings` and its existing storage |
| `VlessGroup` | Immutable members, selected fallback and probe mapping for one connection | Existing `LocationConfig` and subscription ownership; no new database model |
| `ChannelLatency` | One deadline, expected status and cancellation policy | Existing platform HTTP clients, proxy configuration and authentication |
| `XrayGroupConfig` | Add group outbounds, routing and observer JSON | `XrayConfig.buildVless` and the existing platform launch paths |

An audit fixed probes using saved rather than running proxy settings, results
surviving a reconnect, cancellation being swallowed during subscription fetch,
refresh starting before settings were saved, duplicate members differing only
in display names, and iOS configuration errors escaping the connection startup.
The HTTP deadline covers waiting for proxy authentication as well as the request.
The platform clients are shared with subscription fetching rather than duplicated.

Groups contain at most 64 members and use loopback ports 31000–31063 for probes.
Larger lists require Manual or a smaller subscription. On iOS, memory use of large
groups needs measurement under the packet extension limit before release.

Auto-update waits for saved settings, checks due subscriptions on app startup and
every five minutes while the app runs, and uses the shorter of the provider's
interval and the user's maximum interval. Disabled auto-update makes no scheduled
fetches. iOS may suspend the app; this does not promise background execution.
Refreshing adds new servers and retains the selected entry when it still exists.
The connected group's members are a snapshot: reconnect to apply an updated list.

## Validation and device checks

`./gradlew :sharedUI:jvmTest :desktopApp:compileKotlin :androidApp:assembleDebug`
checks common/JVM code, Android packaging, and the membership, routing, bootstrap
DNS and subscription-update regressions. `scripts/check-xray-configs.sh` checks
emitted configs with the pinned Xray binary. These are not an iPhone traffic test.

The manual `PR checks` workflow compiles Kotlin for iOS arm64 and macOS arm64
without signing. This is a compile check, not an IPA build or a device test.

Before releasing, use a signed device build to test Lowest/Balanced with one reachable and
one unreachable VLESS server, then Wi-Fi/cellular changes, subscription refresh,
Telemost latency, and iOS extension memory. If iOS adopts a tunnel after the app
process is relaunched, active-channel latency remains available; the in-memory
per-member probe mapping is restored on the next VPN reconnect.
