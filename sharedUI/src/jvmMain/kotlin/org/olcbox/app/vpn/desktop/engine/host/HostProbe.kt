package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.vpn.desktop.OlcRtcProvider
import org.olcbox.app.vpn.desktop.engine.ProbeEngine
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The desktop's probes on ghostlane-engine, the Core build's program at the
 * pin, run as the app's own child ([HostProcess]).
 *
 * Two kinds of question, both in a process of their own that the app starts
 * and that ends with its answer:
 *
 *  - the room check and the room ping ([check], [ping]), and a single request
 *    through a core line ([probe]): the engine's one-shots (`olc-check`,
 *    `olc-ping`, `probe`), each given a session file of mode 0600 in a
 *    directory of its own, removed when the question has been answered. The
 *    engine prints one line, which [HostProbe.Result] reads; a question that
 *    did not come back is a no and its reason, never an exception.
 *  - smart connect's probe of a line ([starter], [probes]): the probe asks
 *    through a SOCKS5 port, behind the login made up for it, for a 204 and
 *    then for 64 KB whole ([TransportProbe]), which no one-shot gives, so this
 *    core is a throw-away `run` of the engine whose session is the probe's
 *    core.json: started for one probe and stopped by it.
 *
 * A room's one-shot joins the room for real. The only carrier any test of
 * this reaches is the `none` carrier of the engine's own interop pipe.
 *
 * [binary] is the program ([HostAssets.resolve]); [serverAddress] the address
 * of a server named by a hostname, asked on the network underneath
 * ([HostServerNames]); [sessionParent] where the session files go (the
 * system's temporary directory when null); [slackMs] how long past a
 * question's own time the engine is given before it is killed.
 */
internal class HostProbe(
    private val binary: () -> Path = HostAssets::resolve,
    private val sessionParent: Path? = null,
    private val serverAddress: suspend (String, DirectDns?) -> String = HostServerNames::address,
    private val log: (String) -> Unit = {},
    private val slackMs: Long = SLACK_MS,
) {
    /** How a question ended: the milliseconds, or null with why. */
    data class Result(val millis: Long?, val reason: String?)

    /**
     * One room to join: what the session's room half holds. [rooms] is the
     * list the engine walks (the first is joined first); [transport] is the
     * carrier's own when null.
     */
    class Room(
        val provider: String,
        val rooms: List<String>,
        val keyHex: String,
        val deviceId: String,
        val transport: String? = null,
        val dtlsProfile: String = OlcrtcDtls.OFF,
        val vp8Fps: Int? = null,
        val vp8Batch: Int? = null,
    ) {
        /**
         * The room half of the session. The handshake profile is written only
         * when one is asked for; the frame settings only for a VP8 carrier.
         */
        fun half(): JsonObject = buildJsonObject {
            put("provider", provider)
            transport?.let { put("transport", it) }
            put("rooms", JsonArray(rooms.map { JsonPrimitive(it) }))
            put("key", keyHex)
            put("device_id", deviceId)
            if (dtlsProfile != OlcrtcDtls.OFF) put("dtls_profile", dtlsProfile)
            if (vp8Fps != null && vp8Batch != null) {
                putJsonObject("vp8") {
                    put("fps", vp8Fps)
                    put("batch", vp8Batch)
                }
            }
        }

        companion object {
            /**
             * [config] as the engine is to join it: its carrier under the
             * engine's name for it, its room and failover rooms, its key and
             * its transport, and the frame settings when the transport is VP8.
             */
            fun of(config: LocationConfig, deviceId: String, dtlsProfile: String): Room {
                val normalized = config.normalized()
                val vp8 = normalized.transport == LocationConfig.TRANSPORT_VP8CHANNEL
                return Room(
                    provider = OlcRtcProvider.desktopProviderArg(normalized.bypassProvider),
                    rooms = normalized.failoverRooms(),
                    keyHex = normalized.key,
                    deviceId = deviceId,
                    transport = normalized.transport,
                    dtlsProfile = dtlsProfile,
                    vp8Fps = normalized.vp8Fps.takeIf { vp8 },
                    vp8Batch = normalized.vp8Batch.takeIf { vp8 },
                )
            }
        }
    }

    @Volatile private var checked: Path? = null

    /**
     * Joins [room] alone and waits for its SOCKS port within [timeoutMs]
     * (`olc-check`). Blocking: called off the main thread.
     */
    fun check(room: Room, timeoutMs: Long): Result = oneShot(
        question = HostQuestion.OlcCheck,
        command = "olc-check",
        session = HostSession.text(core = null, room = room.half().toString()),
        flags = listOf("--timeout-ms", timeoutMs.toString()),
        timeoutMs = timeoutMs,
    )

    /**
     * Joins [room], then asks for [url] through it, the best of three
     * (`olc-ping`), within [timeoutMs]. Blocking.
     */
    fun ping(room: Room, timeoutMs: Long, url: String): Result = oneShot(
        question = HostQuestion.OlcPing,
        command = "olc-ping",
        session = HostSession.text(core = null, room = room.half().toString()),
        flags = listOf("--url", url, "--timeout-ms", timeoutMs.toString()),
        timeoutMs = timeoutMs,
    )

    /**
     * Asks for [url] once through the line of [core] (`probe`): the core half
     * as the app's builder writes it, [bindInterface] the interface the
     * probe's sockets leave by when a tun holds the default route. Blocking.
     */
    fun probe(core: String, url: String, timeoutMs: Long, bindInterface: String?): Result = oneShot(
        question = HostQuestion.Probe,
        command = "probe",
        session = HostSession.text(core = core, bind = bindInterface),
        flags = listOf("--url", url, "--timeout-ms", timeoutMs.toString()),
        timeoutMs = timeoutMs,
    )

    private fun oneShot(
        question: HostQuestion,
        command: String,
        session: String,
        flags: List<String>,
        timeoutMs: Long,
    ): Result {
        val program: Path
        val dir: Path
        try {
            program = binary()
            checkProgram(program)
            dir = HostProcess.privateDirectory(sessionParent)
        } catch (e: Exception) {
            return Result(null, e.message ?: e.javaClass.simpleName)
        }
        return try {
            val file = HostProcess.writeSession(dir, session)
            val events = HostProcess.ask(program, listOf(command, "--session", file.toString()) + flags, timeoutMs + slackMs)
            answer(question, events, timeoutMs + slackMs)
        } catch (e: Exception) {
            Result(null, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching {
                Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    /** The one line the engine printed, as a yes or a no with its reason. */
    private fun answer(question: HostQuestion, events: List<HostEvent>, waitedMs: Long): Result {
        if (events.isEmpty()) return Result(null, "ghostlane-engine gave no answer within $waitedMs ms")
        events.filterIsInstance<HostEvent.Answer>().firstOrNull { it.question == question }?.let { answer ->
            return if (answer.ok) {
                answer.millis?.let { Result(it, null) } ?: Result(null, "no time came back")
            } else {
                Result(null, answer.error ?: "no reason came back")
            }
        }
        events.filterIsInstance<HostEvent.Failed>().firstOrNull()?.let { return Result(null, "${it.code.word}: ${it.message}") }
        return Result(null, HostEvents.logLine(events.first()) ?: "ghostlane-engine answered with a line this app does not read")
    }

    /** Once per program: a program of another protocol is not asked, and what it says of itself goes into the log. */
    private fun checkProgram(program: Path) {
        if (checked == program) return
        val version = HostAssets.version(program) ?: error("ghostlane-engine printed no version: $program is not a program this app runs")
        HostAssets.refusal(version)?.let { error(it) }
        log(HostAssets.describe(version))
        checked = program
    }

    /**
     * The starter smart connect's probe gets: each probe's core is a
     * throw-away `run` of the engine over the core.json the probe writes,
     * with the interface the caller names bound in its session. The cores it
     * started and that still run are stopped by [Starter.stopAll].
     */
    fun starter(): TransportProbe.RustStarter = Starter()

    /**
     * The probes of a desktop engine on this program: [ProbeEngine.fresh] a
     * core of its own for every probe, [ProbeEngine.kept] the one a session's
     * look for another line uses, which the session's stop stops.
     */
    fun probes(): ProbeEngine = Probes(this)

    private inner class Starter : TransportProbe.RustStarter {
        private val running = ConcurrentHashMap.newKeySet<HostProcess>()

        override suspend fun start(spec: OutboundSpec, config: String, bindInterface: String?): TransportProbe.Core {
            // The run the native start made, kept here as well: when the caller is
            // cancelled while the start waits, the wait ends all the same and
            // withContext throws in place of returning the run, which nobody
            // else could stop.
            val made = AtomicReference<HostProcess?>()
            try {
                withContext(Dispatchers.IO) {
                    val program = binary()
                    checkProgram(program)
                    val run = HostProcess.run(
                        program,
                        HostSession.text(core = config, bind = bindInterface),
                        onEvent = { event -> HostEvents.logLine(event)?.let { log("$NAME: probe: $it") } },
                        onStderr = { text -> log("$NAME: probe: $text") },
                        parent = sessionParent,
                    )
                    made.set(run)
                    running += run
                    when (val start = run.awaitStart(START_WAIT_MS)) {
                        is HostProcess.Start.Ready -> Unit
                        is HostProcess.Start.Ended -> {
                            val why = start.ended?.let { ended ->
                                listOfNotNull(ended.code?.word, ended.message).joinToString(": ").ifEmpty { ended.reason.word }
                            } ?: "it exited with code ${start.exitCode}"
                            error("$NAME did not start the probe: $why")
                        }
                        HostProcess.Start.Waiting -> error("$NAME did not say it served within ${START_WAIT_MS / 1000} s")
                    }
                }
            } catch (e: CancellationException) {
                made.get()?.let { orphan -> withContext(NonCancellable + Dispatchers.IO) { release(orphan) } }
                throw e
            } catch (e: Exception) {
                made.get()?.let { orphan -> withContext(NonCancellable + Dispatchers.IO) { release(orphan) } }
                log("$NAME did not start a probe: ${e.message ?: e}")
                throw e
            }
            val run = checkNotNull(made.get())
            return object : TransportProbe.Core {
                override fun isRunning(): Boolean = run.isAlive()
                override suspend fun stop() = withContext(Dispatchers.IO) { release(run) }
            }
        }

        override suspend fun serverAddress(host: String, resolver: DirectDns?): String? =
            runCatching { this@HostProbe.serverAddress(host, resolver) }.getOrNull()

        override fun refused(spec: OutboundSpec, why: String) = log("$NAME does not probe this line: $why")

        private fun release(run: HostProcess) {
            running -= run
            run.stop()
        }

        /** Stops every core this starter started that still runs. */
        fun stopAll() {
            running.toList().forEach { release(it) }
        }
    }

    private class Probes(program: HostProbe) : ProbeEngine {
        override val fresh: TransportProbe.Starter = program.Starter()
        private val keptStarter = program.Starter()
        override val kept: TransportProbe.Starter = keptStarter

        override fun stopKept() = keptStarter.stopAll()
    }

    private companion object {
        const val NAME = "ghostlane-engine"

        /** Past a question's own time the engine is given this long to print its line; then it is killed. */
        const val SLACK_MS = 10_000L

        /** How long a probe's core is given to say it serves; the probe's own wait for the port comes after. */
        const val START_WAIT_MS = 10_000L
    }
}
