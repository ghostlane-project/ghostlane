#ifndef OLCBOX_TUN2SOCKS_RUN_H
#define OLCBOX_TUN2SOCKS_RUN_H

// What olcbox_tun2socks_run returns when it started nothing because a run is
// in progress. hev's own results are 0 and -1 to -5. The service reads it as
// NativeTunnelRun.RUN_IN_PROGRESS, which has to be the same number.
#define OLCBOX_TUN2SOCKS_RUN_IN_PROGRESS (-100)

// One run of hev-socks5-tunnel on tun_fd, until it is stopped or fails. The
// descriptor is the run's from the call on and is closed before it returns,
// whatever it returns.
int olcbox_tun2socks_run(const char *config_path, int tun_fd);

#endif
