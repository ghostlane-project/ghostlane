# Lowest at connect

An opt-in `auto_select_lowest` flag in the existing subscription settings enables
app-side selection. Manual remains the default. The coordinator reads the selected
subscription, ranks its eligible entries using existing address probes while the
VPN is disconnected, then starts exactly one ordinary platform connection.

There are no changes to Xray/sing-box configuration, native bridges or core
selection. TCP Reality still uses sing-box on iOS. No observatory, balancer,
per-member SOCKS listeners, new dependency or server component is added.

## Selection and failure

- Up to 32 native entries are probed, at most three concurrently, within an
  overall six-second ranking budget. Remaining/failed probes are unknown, not
  proof of an unusable VPN. The selected entry wins among unknowns and ties;
  other ties retain provider order. Aliases of a connection are deduplicated.
- These are the platform's existing ICMP/TCP address checks, not throughput or
  authentication tests. The fastest measured address need not be the fastest
  working tunnel. No additional room is joined to rank olcRTC; unmeasured rooms
  stay eligible in fallback order. Different probe methods are not equivalent
  end-to-end latency measurements.
- Once connected, keep that exit. Native reconnect/migration handling continues
  to run. Only a terminal platform Error or a 90-second establishment timeout
  permits trying the next entry, after the old tunnel stops and a 15-second
  cooldown. There are at most three connection attempts per invocation.
- An HTTP measurement failure alone never triggers failover. A silent stalled
  tunnel that the platform still reports Connected is therefore not detected by
  this policy; an independent tunnel-health design is needed for that case.
- Manual stop, manual selection, settings disabling Lowest and OS disconnect
  cancel/end the selection attempt. A later connection started from system
  settings wins over a pending ranking/cooldown. Deleted or replaced subscription
  entries are rechecked before start and are never restored from the snapshot.

The coordinator lives in the app view model, not the packet extension. It does
not promise background failover after iOS suspends or terminates the app, or
automatic selection for connections initiated outside this view model. Manual
selection while connected explicitly overrides the plan. Balanced with a random
exit per new connection is intentionally absent; ordinary connections retain
their exit IP until a failure requires reconnecting.

## Verification and release

Tests cover same-subscription selection, preserved exit during migration,
terminal failure and cooldown, cancellation during ranking/cooldown, deleted
fallbacks, finite retries, unknown ICMP results, no room probes, disabling the
setting, an OS stop and an externally started connection. A view-model test
checks Stop while ranking. The test fake supplies platform status transitions;
this does not substitute for a real iPhone/network run.

Before release, validate TCP Reality, XHTTP and Telemost on signed device builds,
user stop/selection races, Wi-Fi/LTE migration, and app suspension. No measured
iOS memory guarantee is claimed. The architectural memory benefit is that the
extension continues running one unchanged native connection.
