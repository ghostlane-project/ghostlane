package org.olcbox.app.vpn

/**
 * The one native run of the Android tun a process may have, by its thread.
 *
 * hev-socks5-tunnel and the lwIP inside it keep their state in the process:
 * one netif, one set of connection lists and pools, one pair of descriptors a
 * stop is written to. A run started beside one that has not returned shares
 * all of it, and lwIP aborts the app ("netif already added"). A run is over
 * when its native call returns, which is when its thread ends, and that can be
 * after the service has stopped waiting for it and after the service itself is
 * gone. So the thread is kept here, one object for the process, and nothing
 * starts a run while it lives.
 *
 * The native side refuses the same start by a flag of its own
 * (olcbox_tun2socks_run.c): what is known here is a Java thread, and that is
 * where the run is.
 */
class NativeTunnelRun {
    @Volatile
    private var thread: Thread? = null

    /** The thread of the run in progress; null when none was started or the last has returned. */
    fun inProgress(): Thread? = thread?.takeIf { it.isAlive }

    /** Whether a run may start: there is none in progress. */
    fun mayStart(): Boolean = inProgress() == null

    /** [run] is the thread a run has just been started on. */
    fun started(run: Thread) {
        thread = run
    }

    /**
     * After a wait for [run] to end, with what the wait saw. The run is
     * forgotten only when it was seen to end. A wait that timed out says one
     * thing, that the run is still there, and forgetting it then is what let
     * the next start put a second run beside it. A wait that was for an older
     * run, or for none, says nothing about the one in progress.
     */
    fun waited(run: Thread?, ended: Boolean) {
        if (ended && thread === run) thread = null
    }

    companion object {
        /**
         * What the native start returns when it did not enter hev because a
         * run is in progress: OLCBOX_TUN2SOCKS_RUN_IN_PROGRESS in
         * olcbox_tun2socks_run.h, which has to be this number.
         */
        const val RUN_IN_PROGRESS = -100
    }
}
