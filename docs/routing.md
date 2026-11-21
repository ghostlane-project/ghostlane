# Routing

Which traffic goes through the tunnel and which goes straight out, mode by mode and platform by platform. [Back to the README](../README.MD).

Settings → Routing, stored beside the server lists:

| Mode | What goes where |
| --- | --- |
| All traffic through the tunnel | Everything. The exit is the server's, for every connection. |
| Bypass Russia | Russian sites, Russian top-level domains and the local network go straight out; everything else rides the tunnel, name resolution included. |
| Bypass Iran | The same, for Iranian destinations. |
| Bypass China | The same, for Chinese destinations. |
| Only blocked sites through the tunnel | The inverse: sites blocked in Russia, and services that shut Russian users out, ride the tunnel; everything else goes straight out. |

In any mode you can add rules of your own: a domain, which covers its
subdomains, or an address or prefix that always goes direct or always through
the tunnel. They win over the mode's lists.

The lists ship inside the app and are pinned by hash, so no mode needs anything
from the network to start working; on the networks they are for, the lists are
as likely to be unreachable as anything else. The bypass modes use v2fly's
`category-ru`, `tld-ru` and `geoip-ru`; `category-ir` and `geoip-ir`; `cn`,
`tld-cn` and `geoip-cn`. Blocked-only uses
[Re:filter](https://github.com/1andrevich/Re-filter-lists) (MIT), which lists
whole address ranges of some large services, so other sites hosted there ride
the tunnel too.

## Where it applies

| Platform | What applies |
| --- | --- |
| Android, TUN and proxy | Every mode and your rules. An olcRTC room applies them itself, from the engine's direct rules, which say "these go direct" and nothing else. So in a room blocked-only carries everything, and a tunnel rule can only take a site out of the region's list: a site under a whole-TLD entry such as `domain:ru` stays direct |
| iOS | All traffic, or Bypass Russia; an olcRTC room applies it from the engine's direct rules |
| macOS, proxy and tunnel | Every mode and your rules |
| Windows | Every mode and your rules in proxy mode. The Windows tunnel carries everything, and the routing screen says to use proxy mode for it |
| Linux | olcRTC rooms: a bypass and your rules, as in a room on Android. The engine runs as root there, and root keeps its own route out of the tunnel. Other servers carry everything: their cores run as the user and reach the server bound to the physical interface, and the rules have not been moved there. The routing screen says so and does not offer blocked-only |
