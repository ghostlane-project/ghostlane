package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.SessionFailover
import org.olcbox.app.net.SmartConnect
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.DesktopSocksProxySettings

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
 * The local SOCKS listener a session's tun, or in proxy mode its system proxy
 * setting, was pointed at when the session started: a port, and the login it
 * is asked for there, if any.
 *
 * It is fixed for the session, because what points at it cannot be pointed
 * anywhere else while it lives. So it is the line that is made to fit the
 * endpoint and never the other way round: a line that comes later in the
 * session listens on this port and demands this login, whatever its own port
 * and login would have been.
 */
internal data class SessionEndpoint(val port: Int, val login: SocksLogin?)

/**
 * What a session was built with, of the things that cannot be changed behind
 * it. Another location chosen while it runs is judged against this
 * ([LineSupervision.changeOfLine]).
 */
internal data class SessionBuild(
    val mode: DesktopMode,
    val routing: RoutingSettings,
    val socks: DesktopSocksProxySettings,
    /**
     * Whether the tun lets every binary a line can run out of itself. False
     * only for a macOS tun that had to start without the name of the physical
     * interface: that one keeps its first line's server out by address and
     * nothing else, and the core of another server would dial into the tunnel
     * it is there to carry.
     */
    val letsEveryLineOut: Boolean = true
)

/** What becomes of a session when another location is chosen while it runs. */
internal enum class LineChange {
    /** Only the line is replaced. The tun, or the system's proxy setting, is left exactly as it is. */
    BehindTun,

    /** No session is held: nothing has carried traffic yet, so there is nothing to stay behind. */
    NoSession,

    /** Tunnel and proxy are two different things to hold, and one is not made into the other. */
    ModeChanged,

    /** On macOS the routing is in the tun's own config, and everywhere it decides where the rules live. */
    RoutingChanged,

    /**
     * A session that started in a room took its endpoint from the SOCKS
     * settings, and a change of them is meant to take effect.
     */
    SocksChanged,

    /** The tun's process is gone, and its routes with it. */
    TunNotRunning,

    /** See [SessionBuild.letsEveryLineOut]. */
    TunBuiltForOneLine
}

/**
 * The olcRTC engine of the line that stopped answering, as far as a session
 * that looks for another line has to know it. It matters in the Linux tunnel,
 * where an engine runs as root and the app does not.
 */
internal enum class FailedEngine {
    /** The line is a core and has no engine. */
    None,

    /** A room whose engine is down: every attempt at it starts one, and so asks for the password already. */
    Down,

    /** A room whose engine runs and carries nothing. */
    Running
}

/**
 * The lines a desktop session may go to by itself, of those the common rule
 * names for it ([SessionFailover.candidates]), in that rule's order, and what
 * was taken out of them ([LineSupervision.linesToTry]).
 */
internal data class LinesToTry(
    val steps: List<SmartConnect.Step>,
    /** How many olcRTC rooms were left out because starting their engine asks for a password. */
    val roomsLeftOut: Int = 0,
    /** The tun lets no line but its first out of itself, so nothing can be tried behind it. */
    val tunLetsNoOtherLineOut: Boolean = false,
    /** Nothing is tried, because it would begin with stopping an engine that runs as root. */
    val rootEngineRuns: Boolean = false
)

/**
 * The rules of a desktop session one of whose processes died, whose location
 * is changed, or whose line stays down until the session goes to another by
 * itself, kept apart from the manager so they can be read and tested
 * without a desktop.
 *
 * A session is two halves: the tun, which claims the machine's traffic, and
 * the line behind it, which carries it, a sing-box or Xray core or the olcRTC
 * engine. The tun's upstream is one local port, written into its config when it
 * starts (`SingBoxConfig.buildDesktopTun`; hev's yaml on Linux) and fixed for as
 * long as it lives. So a line that dies leaves the tun pointing at a port nobody
 * listens on: packets still enter it and have nowhere to go, which is closed.
 * What opens it is taking the tun down, to recover or to go to another
 * location, and the app used to do both by its own hand.
 */
internal object LineSupervision {
    /**
     * The endpoint a session gets from its first line: what its tun, or its
     * system proxy setting, is pointed at when it starts.
     *
     * A core listens without a login, and so does the sing-box front that
     * stands before the engine in proxy mode under rules ([frontPort]). Only
     * the engine reached directly asks for one, the one in the SOCKS settings,
     * and whatever points at it sends it: the tun's outbound on Windows and
     * macOS, hev's config on Linux, the system's proxy setting.
     *
     * A login is a username. The tun's outbound, the system's proxy setting
     * and the engine's own yaml all send or demand one exactly when the
     * username is not blank, whatever the password is.
     */
    fun endpointOf(
        isOlcrtc: Boolean,
        linePort: Int,
        frontPort: Int?,
        username: String,
        password: String
    ): SessionEndpoint {
        val asksForLogin = isOlcrtc && frontPort == null && username.isNotBlank()
        return SessionEndpoint(
            port = frontPort ?: linePort,
            login = if (asksForLogin) SocksLogin(username, password) else null
        )
    }

    /**
     * Whether another location chosen inside [session] is started behind what
     * the session holds, and if not, why it is a full restart: the teardown and
     * the start that every change of location used to be.
     *
     * Nothing here is weighed. What was read to build the session is compared
     * with what would be read now, whole, and any difference is a full
     * restart. That holds for the routing settings even where a change of them
     * would only have reached the line: which part of them is baked into which
     * tun is a rule that would have to be kept true on three systems, and
     * being wrong about it leaves a tun built for settings nobody has.
     *
     * [tunRunning] is asked of the tun's own process. In proxy mode nothing
     * that is held can stop by itself, and the caller says true.
     */
    fun changeOfLine(
        session: SessionBuild?,
        mode: DesktopMode,
        routing: RoutingSettings,
        socks: DesktopSocksProxySettings,
        tunRunning: Boolean
    ): LineChange {
        if (session == null) return LineChange.NoSession
        return when {
            mode != session.mode -> LineChange.ModeChanged
            routing != session.routing -> LineChange.RoutingChanged
            !sameListener(socks, session.socks) -> LineChange.SocksChanged
            !tunRunning -> LineChange.TunNotRunning
            !session.letsEveryLineOut -> LineChange.TunBuiltForOneLine
            else -> LineChange.BehindTun
        }
    }

    /** The LAN half of the settings is applied without a restart, and is no part of the listener. */
    private fun sameListener(now: DesktopSocksProxySettings, then: DesktopSocksProxySettings): Boolean =
        now.host == then.host && now.port == then.port &&
            now.username == then.username && now.password == then.password

    /**
     * Whether a line the session goes to by itself, when its own has stopped
     * answering, can be started behind what the session holds. It is
     * [changeOfLine] with the settings taken from the session itself, and so
     * never one of its three answers about settings.
     *
     * The difference from a location the user chose is what a no means. There
     * it is the full restart, which the user's own request then makes. Here
     * nobody asked, and a full restart takes the tun down, which is the one
     * thing a move exists to avoid: a no is no move.
     *
     * The settings are left out because a move applies none. The session goes
     * on as it was built, and settings changed since then are applied by the
     * request the user's own gesture sends, which supersedes whatever a look
     * is doing. Weighing them here would only turn a request that is on its
     * way into a move that is not made.
     */
    fun moveBehindTun(session: SessionBuild, tunRunning: Boolean): LineChange =
        changeOfLine(session, session.mode, session.routing, session.socks, tunRunning)

    /**
     * Which of [candidates], the lines the common rule names in place of one
     * that stopped answering, a session built as [session] may try by itself.
     * The order is the rule's and is kept, and a desktop only takes lines out.
     *
     * Behind a tun that lets only its first line out
     * ([SessionBuild.letsEveryLineOut]) it takes out all of them: neither a
     * probe's core nor another line's would get out of that tun.
     *
     * In the Linux tunnel it takes out whatever ends in a password dialog.
     * The engine runs as root there, starting one asks for the
     * administrator's password, and a dialog that opens by itself, for a move
     * nobody asked for, is not acceptable. What that leaves depends on the
     * engine of the line that failed ([engine]). A session on a core is not
     * moved into a room. A session in a room whose engine is down may go
     * anywhere: every attempt at its own line opens that dialog already, and
     * a room in its place opens one more of the same. A session in a room
     * whose engine runs and carries nothing stays there: trying any line in
     * its place begins with stopping that engine, and a line that then does
     * not carry leaves the session's own to come back through the dialog.
     * Nor is it known how soon an engine that runs as root is gone once a
     * process that does not has told it to stop, and until it is gone no
     * other line can take its port.
     */
    fun linesToTry(
        candidates: List<SmartConnect.Step>,
        session: SessionBuild,
        engine: FailedEngine
    ): LinesToTry {
        if (!session.letsEveryLineOut) return LinesToTry(emptyList(), tunLetsNoOtherLineOut = true)
        if (session.mode != DesktopMode.LinuxTun) return LinesToTry(candidates)
        return when (engine) {
            FailedEngine.Down -> LinesToTry(candidates)
            FailedEngine.Running -> LinesToTry(emptyList(), rootEngineRuns = true)
            FailedEngine.None -> {
                val withoutRooms = candidates.filterNot { it.entry.location.kind == LocationKind.Olcrtc }
                LinesToTry(withoutRooms, roomsLeftOut = candidates.size - withoutRooms.size)
            }
        }
    }

    /**
     * The outage of a line after an attempt at it failed at [nowMs]: begun by
     * the first failure, and one failure longer with each one after it.
     *
     * Only with a network. A machine whose cable is out is not a dead server,
     * every other line would fail the same way, and the time it lasts is not
     * time the line was down: the count is dropped, and begins again with the
     * first attempt that fails once the network is back.
     */
    fun afterFailedAttempt(
        outage: SessionFailover.Outage?,
        nowMs: Long,
        networkPresent: Boolean
    ): SessionFailover.Outage? = when {
        !networkPresent -> null
        outage == null -> SessionFailover.Outage(startedAtMs = nowMs, failedAttempts = 1)
        else -> outage.another()
    }

    /**
     * The outage after a look at the other lines that moved the session
     * nowhere. The look goes on record, which puts the next one off by
     * [SessionFailover.REPEAT_MS], only when it [ranToItsEnd] with a network
     * still under it. One that was cut short, by another request or by the
     * network going half way, tried nothing: on record it would put the next
     * look off by five minutes while the dead line is retried.
     */
    fun afterLook(
        outage: SessionFailover.Outage?,
        nowMs: Long,
        ranToItsEnd: Boolean,
        networkPresent: Boolean
    ): SessionFailover.Outage? =
        if (ranToItsEnd && networkPresent) outage?.copy(lastPassAtMs = nowMs) else outage

    /**
     * Whether the death of [which] is recovered behind the tun, which stays
     * exactly as it is while the process is started again on the port it had.
     *
     * A core and the engine are. The tun's own death cannot be: its routes went
     * with its process, there is nothing left to restart behind, and the
     * machine's traffic already leaves directly. Saying "reconnecting" over that
     * would claim a hold that is not there, so it ends the session as it always
     * has. The Linux kill switch ([LinuxKillSwitch]) changes where the traffic
     * goes then, not this: the session still ends, with its block left standing.
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
