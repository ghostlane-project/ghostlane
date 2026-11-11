#include <stdatomic.h>
#include <unistd.h>

#include "hev-main.h"
#include "olcbox_tun2socks_run.h"

// Set for as long as a run is inside hev. hev-socks5-tunnel and its lwIP keep
// their state in the process, not in a run: one netif, one set of connection
// lists and pools, one pair of descriptors a stop is written to. A second run
// that enters while the first has not returned shares all of it, and lwIP
// aborts the process at the second netif_add ("netif already added"). The
// service does not start one while it knows of a live native thread
// (NativeTunnelRun), but what it knows is a Java thread, and this is where
// the run is. So a start beside a run is refused here, before hev is entered.
static atomic_flag run_in_progress = ATOMIC_FLAG_INIT;

int olcbox_tun2socks_run(const char *config_path, int tun_fd)
{
    if (atomic_flag_test_and_set(&run_in_progress)) {
        close(tun_fd);
        return OLCBOX_TUN2SOCKS_RUN_IN_PROGRESS;
    }

    int result = hev_socks5_tunnel_main_from_file(config_path, tun_fd);
    atomic_flag_clear(&run_in_progress);
    close(tun_fd);
    return result;
}
