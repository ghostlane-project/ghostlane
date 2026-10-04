package org.olcbox.app.vpn.desktop

/** Which of a connected desktop session's processes died. */
internal enum class DeadProcess {
    /** A sing-box or Xray core: the line's own, or the sing-box front before the olcRTC engine. */
    Core,

    /** The olcRTC engine. */
    Engine,

    /** What holds the tun: hev-socks5-tunnel on Linux, the tun's own sing-box on Windows and macOS. */
    Tun
}

/**
 * The rules of a desktop session one of whose processes died, kept apart from
 * the manager so they can be read and tested without a desktop.
 *
 * A session is two halves: the tun, which claims the machine's traffic, and
 * the line behind it, which carries it, a sing-box or Xray core or the olcRTC
 * engine. The tun's upstream is one local port, written into its config when it
 * starts (`SingBoxConfig.buildDesktopTun`; hev's yaml on Linux) and fixed for as
 * long as it lives. So a line that dies leaves the tun pointing at a port nobody
 * listens on: packets still enter it and have nowhere to go, which is closed.
 * What opens it is taking the tun down to recover, and the app used to do that
 * by its own hand.
 */
internal object LineSupervision {
    /**
     * Whether the death of [which] is recovered behind the tun, which stays
     * exactly as it is while the process is started again on the port it had.
     *
     * A core and the engine are. The tun's own death cannot be: its routes went
     * with its process, there is nothing left to restart behind, and the
     * machine's traffic already leaves directly. Saying "reconnecting" over that
     * would claim a hold that is not there, so it ends the session as it always
     * has.
     */
    fun restartsBehindTun(which: DeadProcess): Boolean = when (which) {
        DeadProcess.Core, DeadProcess.Engine -> true
        DeadProcess.Tun -> false
    }

    /**
     * How long to wait before restart attempt [attempt], counted from 0: 2, 4,
     * 8 and 16 seconds, then 30 for as long as the session lasts.
     *
     * Short at first because the usual death is a crash or a kill, which one
     * restart answers, and capped because a server that is gone must not turn
     * into a process started every few seconds, each of which, for an olcRTC
     * room, is a join the meeting service sees. Never zero: every outage counts
     * from 0 again, so a process that came back and died at once would
     * otherwise be restarted without a pause.
     */
    fun backoffMs(attempt: Int): Long = when {
        attempt <= 0 -> FIRST_BACKOFF_MS
        attempt >= STEADY_FROM_ATTEMPT -> STEADY_BACKOFF_MS
        else -> FIRST_BACKOFF_MS shl attempt
    }

    private const val FIRST_BACKOFF_MS = 2_000L
    private const val STEADY_BACKOFF_MS = 30_000L

    /** The first attempt whose doubled wait, 32 s, would pass the cap. */
    private const val STEADY_FROM_ATTEMPT = 4
}
