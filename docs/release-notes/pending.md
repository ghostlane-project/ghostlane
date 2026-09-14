### iOS: the tunnel under speed tests

The previous release moved the death from the upload phase to the download phase; this one changes the shape rather than the numbers. For xhttp locations the tunnel extension no longer runs two network engines in its 50 MB: the small native tun2socks that every Xray-based iOS client uses now sits in front of the transport engine, and the receive window that grew without bound under a download is capped. The tunnel's DNS is answered by the transport engine itself, and Bypass Russia works on this path with the same three lists as before. olcRTC locations keep the previous arrangement with the slimmer engine from 1.0.424.

### Diagnostics

The exported log now names what the tunnel process is busy with at the moment the system reports memory pressure, and once when its footprint passes 38 MB, instead of only once a minute. The log's engine label says which arrangement really ran.
