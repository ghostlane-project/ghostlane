### Lowest, chosen before you connect

A new switch in the subscription settings, off by default. With it on, Connect ranks the servers of the selected subscription by the app's own address probes while the tunnel is still down, then starts one ordinary connection to the fastest and keeps that exit. Only a connection that fails outright, or one that does not come up within 90 seconds, moves on to the next server, after a 15-second pause and at most three times. Manual selection stays the default, olcRTC rooms are never joined just to rank them, and nothing in the cores or the packet extension changed.

Contributed by @igves96 (#36, closes #30).
