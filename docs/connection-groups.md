# VLESS connection groups and channel latency

In Subscription settings, Manual keeps the existing single-location behavior.
Lowest and Balanced use the VLESS entries from the selected location's subscription.
Standalone links and olcRTC rooms remain manual. Supported VLESS transports are
those the parser already supports: TCP and XHTTP, with their existing TLS/Reality settings.

One Xray process owns the group. Lowest uses the leastPing strategy; Balanced
randomly selects an observed available member for each new connection. Existing
connections retain their outbound. Balanced can use different exit IPs concurrently;
it does not combine bandwidth for a single connection. While the observatory warms
up, the first member is the fallback. A failed group never falls back to direct internet.
The user's Bypass Russia routing remains a separate, explicit routing choice.

Xray probes members through their VLESS outbounds with an HTTPS 204 request every
minute. The UI's Measure action uses a separate loopback SOCKS inbound pinned to
that member, so a successful neighbor cannot make a failed member look healthy.
These HTTP measurements are available while the group is connected. Disconnected
address probes remain address probes; they do not prove the VPN credentials work.
Cached results are cleared when the connection session changes.

The connected status also shows HTTP response time, refreshed every 30 seconds
while the home screen is composed. For Telemost this request goes through the
existing media session. It does not join another room or consume another seat.
An unanswered probe displays a dash and does not trigger a reconnect. The number
includes request setup and the destination's response time; it is neither ICMP RTT
nor a download speed test. Failure of the probe destination alone can affect it.

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

Before releasing, build on macOS and test Lowest/Balanced with one reachable and
one unreachable VLESS server, then Wi-Fi/cellular changes, subscription refresh,
Telemost latency, and iOS extension memory. If iOS adopts a tunnel after the app
process is relaunched, active-channel latency remains available; the in-memory
per-member probe mapping is restored on the next VPN reconnect.
