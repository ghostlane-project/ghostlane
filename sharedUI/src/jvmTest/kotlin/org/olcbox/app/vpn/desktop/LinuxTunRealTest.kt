package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.SessionHooks
import org.olcbox.app.vpn.desktop.engine.host.HostEndReason
import org.olcbox.app.vpn.desktop.engine.host.HostEngine
import org.olcbox.app.vpn.desktop.engine.host.HostEvent
import org.olcbox.app.vpn.desktop.engine.host.HostEvents
import org.olcbox.app.vpn.desktop.engine.host.HostProcess
import org.olcbox.app.vpn.desktop.engine.host.HostSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K2 on ghostlane-engine: the Linux tunnel as the app makes it, in a network
 * namespace of its own. LinuxTunController writes hev's file and the up and
 * down scripts and runs hev through `sudo -n`, as it does on a desktop where
 * the user may; the engine is the app's own child, started by HostEngine for
 * a core line and by HostProcess for a room behind its front, and runs as
 * the user, like this test. Every socket of the user but loopback's goes into
 * the tun; the engine's leave by the namespace's way out, bound to it.
 *
 * Run by scripts/desktop-checks/linux-tun.sh (rust-desktop.yml's Linux leg),
 * which makes the namespaces, builds hev from the app's tree, starts the far
 * side (a DNS server, an HTTP origin, a UDP echo, Xray 26.4.25 serving a
 * VLESS REALITY Vision line, the olcRTC server in a room on the `none`
 * carrier's hub) and writes what these tests read into the file [CHECK_ENV]
 * names. Anywhere else each test is skipped, and the script fails a run in
 * which one was skipped or did not run.
 *
 * The client namespace leaves by [WAY_OUT] from [OUT4] and [OUT6]; whatever
 * the far side sees from those addresses left the machine outside the
 * tunnel. A connection the line or the room carried reaches the origin from
 * the far side's own addresses.
 */
class LinuxTunRealTest {
    private class Check(
        val engine: String,
        val hev: String,
        val link: String,
        val room: JsonObject,
        val appData: String,
        val logs: File,
        val xrayAccess: File,
        val resolvectl: File?,
        val outcomes: File,
    ) {
        companion object {
            fun read(file: File): Check {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                return Check(
                    engine = s("engine"),
                    hev = s("hev"),
                    link = s("link"),
                    room = o.getValue("room").jsonObject,
                    appData = s("app_data"),
                    logs = File(s("logs")),
                    xrayAccess = File(s("xray_access")),
                    resolvectl = s("resolvectl").takeIf { it.isNotBlank() }?.let { File(it) },
                    outcomes = File(s("outcomes")),
                )
            }
        }
    }

    private lateinit var check: Check
    private lateinit var sessions: Path
    private val log = Collections.synchronizedList(mutableListOf<String>())

    private val host = DesktopEngineHost(
        log = { line -> say(line) },
        windowsTunOutput = {},
        session = object : SessionHooks {
            override fun lineDied(what: String) = say("the line died: $what")
            override fun wayOutChanged(interfaceName: String?) = say("the way out changed: $interfaceName")
        },
    )

    private var engine: HostEngine? = null
    private var front: HostProcess? = null
    private var tun: LinuxTunController? = null
    private var tunProcess: Process? = null

    @BeforeTest
    fun onlyUnderTheCheck() {
        val file = System.getenv(CHECK_ENV)
        Assume.assumeTrue("set by scripts/desktop-checks/linux-tun.sh", !file.isNullOrBlank())
        check = Check.read(File(requireNotNull(file)))
        System.setProperty("olcbox.appDataDir", check.appData)
        sessions = Files.createTempDirectory("linux-tun-real")
        val left = leftovers()
        assertTrue(left.isEmpty(), "a test began over what another one left: $left")
    }

    @AfterTest
    fun clean() {
        runCatching { front?.stop() }
        runCatching { engine?.line?.stopNow() }
        tun?.let { controller ->
            runBlocking {
                runCatching {
                    controller.endHold()
                    controller.stop(tunProcess)
                }
            }
        }
        if (::sessions.isInitialized) sessions.toFile().deleteRecursively()
    }

    @Test
    fun aCoreLineCrossesTheTunnelTheAppMakes(): Unit = runBlocking {
        val before = rpFilter()
        controls("/core")
        val login = SocksLogin("tun-${nonce()}", "pass-${nonce()}")
        val port = freePort()
        val engine = startLine(port, login)
        val asked = resolvectlAsked().size
        val process = startTunnel(port, login)
        assertRulesStand(killSwitch = false)
        assertResolvedTold(asked)
        val engineUid = uidOf(engine.line.runningProcesses().first().pid())
        val hev = hevPids(process)
        assertTrue(engineUid != null && engineUid != 0, "the engine runs as the user, not root: uid $engineUid")
        assertTrue(hev.isNotEmpty() && hev.all { uidOf(it) == 0 }, "hev runs as root: ${hev.map { it to uidOf(it) }}")

        val byAddress = fetchPage(ORIGIN, "/core/address")
        assertTrue(page(byAddress), "TCP by address through the tunnel: $byAddress\n${tail()}")
        assertFarSide("/core/address", "TCP by address")
        assertTrue(
            waitUntil(3_000) { ORIGIN in lineCarried() },
            "the line carried it, its own connection from $OUT4: ${check.xrayAccess.readText().takeLast(1500)}"
        )

        val dnsMark = queries(LINE_NAME).size
        val mapped = mapped(LINE_NAME)
        val byName = fetchPage(mapped, "/core/name", LINE_NAME)
        assertTrue(page(byName), "TCP by name through the tunnel ($LINE_NAME at $mapped): $byName\n${tail()}")
        assertFarSide("/core/name", "TCP by name")
        val nameAsked = queries(LINE_NAME).drop(dnsMark)
        assertTrue(
            nameAsked.isNotEmpty() && OUT4 !in nameAsked,
            "$LINE_NAME was asked by the far side, never from $OUT4: $nameAsked"
        )
        assertTrue(
            waitUntil(3_000) { LINE_NAME in lineCarried() },
            "the line carried the name as a name: ${check.xrayAccess.readText().takeLast(1500)}"
        )

        assertDatagramCrosses("a core line")
        val refused = assertIpv6Refused("/core/v6")

        tun!!.stop(process)
        assertClean(before, "a core line's tunnel, stopped")
        engine.line.stopNow()
        assertTrue(engine.line.runningProcesses().isEmpty(), "the engine is gone once stopped")
        outcome(
            "a core line in the tunnel: TCP by address and by name, a datagram, IPv6 refused in $refused; " +
                "the engine as uid $engineUid, its line from $OUT4 by $WAY_OUT; hev as root; nothing left after the stop"
        )
    }

    @Test
    fun aRoomBehindItsFrontCrossesTheTunnel(): Unit = runBlocking {
        val before = rpFilter()
        val login = SocksLogin("front-${nonce()}", "pass-${nonce()}")
        val port = freePort()
        val joins = world("hub").count { it[0] == "join" }
        val events = Collections.synchronizedList(mutableListOf<HostEvent>())
        val frontRun = HostProcess.run(
            Path.of(check.engine),
            frontSession(port, login, "l2-front-${nonce()}"),
            onEvent = { event ->
                events += event
                HostEvents.logLine(event)?.let { say("front: $it") }
            },
            onStderr = { line -> say("front: $line") },
            parent = sessions,
        )
        front = frontRun
        val start = frontRun.awaitStart(ROOM_WAIT_MS)
        assertTrue(start is HostProcess.Start.Ready, "the front is ready with both halves: $start\n${tail()}")
        val bound = events.toList().filterIsInstance<HostEvent.Bound>()
        assertEquals(WAY_OUT, bound.firstOrNull()?.interfaceName, "the front's sockets are bound to the way out")
        assertTrue(
            waitUntil(10_000) { events.toList().any { it is HostEvent.SessionOpened } },
            "the room's session opened: $events"
        )
        assertTrue(
            waitUntil(5_000) { world("hub").filter { it[0] == "join" }.drop(joins).any { it[1] == OUT4 } },
            "the room was joined from $OUT4, by the way out: ${world("hub").drop(joins)}"
        )
        val engineUid = uidOf(frontRun.child.pid())
        assertTrue(engineUid != null && engineUid != 0, "the engine runs as the user, not root: uid $engineUid")

        val process = startTunnel(port, login)
        assertRulesStand(killSwitch = false)

        val byAddress = fetchPage(ORIGIN, "/front/address")
        assertTrue(page(byAddress), "TCP by address through the tunnel and the room: $byAddress\n${tail()}")
        assertFarSide("/front/address", "TCP by address, by the room")

        val roomMark = queries(ROOM_NAME).size
        val byName = fetchPage(mapped(ROOM_NAME), "/front/name", ROOM_NAME)
        assertTrue(page(byName), "TCP by name through the tunnel and the room: $byName\n${tail()}")
        assertFarSide("/front/name", "TCP by name, by the room")
        val roomAsked = queries(ROOM_NAME).drop(roomMark)
        assertTrue(
            roomAsked.isNotEmpty() && OUT4 !in roomAsked,
            "$ROOM_NAME was asked by the room's far side, never from $OUT4: $roomAsked"
        )

        val directMark = queries(DIRECT_NAME).size
        val direct = fetchPage(mapped(DIRECT_NAME), "/front/direct", DIRECT_NAME)
        assertTrue(page(direct), "the name the front's rules send direct: $direct\n${tail()}")
        val directSeen = requests("/front/direct")
        assertTrue(
            directSeen.isNotEmpty() && directSeen.all { it == OUT4 },
            "the direct name reached the origin from $OUT4 alone, by the way out: $directSeen"
        )
        val directAsked = queries(DIRECT_NAME).drop(directMark)
        assertTrue(
            directAsked.isNotEmpty() && directAsked.all { it == OUT4 },
            "the front's own resolver asked for $DIRECT_NAME from $OUT4: $directAsked"
        )

        assertDatagramCrosses("a room behind its front")
        val refused = assertIpv6Refused("/front/v6")

        tun!!.stop(process)
        assertClean(before, "a room's tunnel, stopped")
        val lefts = world("hub").count { it[0] == "left" }
        frontRun.stop()
        assertEquals(0, frontRun.exitCode(), "told to stop, the front ends with 0")
        assertEquals(HostEndReason.StdinClosed, frontRun.ended?.reason, "its last line says the stop was asked for")
        assertTrue(
            waitUntil(5_000) { world("hub").filter { it[0] == "left" }.drop(lefts).any { it[1] == OUT4 } },
            "the front left its room when it stopped"
        )
        outcome(
            "a room behind its front in the tunnel: TCP by address and by name by the room, the direct name " +
                "from $OUT4, a datagram by the room, IPv6 refused in $refused; the engine as uid $engineUid; " +
                "nothing left after the stop"
        )
    }

    @Test
    fun killedOutrightNothingIsLeftOnceTheAppStops(): Unit = runBlocking {
        val before = rpFilter()
        val login = SocksLogin("kill-${nonce()}", "pass-${nonce()}")
        val port = freePort()
        val engine = startLine(port, login)
        val process = startTunnel(port, login)
        val up = fetchPage(ORIGIN, "/kill/up")
        assertTrue(page(up), "the tunnel carries before anything is killed: $up\n${tail()}")

        // The engine goes: what the tun takes then goes nowhere.
        val child = engine.line.runningProcesses().first()
        child.destroyForcibly()
        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "the engine was killed")
        val lost = fetch(ORIGIN, "/kill/engine-gone", timeoutMs = 4_000)
        assertTrue(
            !page(lost) && requests("/kill/engine-gone").isEmpty(),
            "with the engine killed nothing reaches the far side: $lost"
        )

        // hev goes the same way, and its pre-down never runs.
        val hev = hevPids(process)
        assertTrue(hev.isNotEmpty(), "hev's process is found")
        hev.forEach { asRoot("kill", "-9", it.toString()) }
        assertTrue(waitUntil(5_000) { !device(LinuxTunController.TUN_NAME) }, "hev killed, the tun is gone")
        val standing = leftovers()
        assertTrue(
            standing.any { "lookup ${LinuxTunController.ROUTE_TABLE}" in it },
            "the rules outlive a killed hev, which is what the app's stop is for: $standing"
        )

        tun!!.stop(process)
        assertClean(before, "after kill -9 and the app's stop")
        val after = fetchPage(ORIGIN, "/kill/after")
        assertTrue(page(after), "the machine's own way is back: $after")
        assertEquals(listOf(OUT4), requests("/kill/after").distinct(), "and it leaves by the way out")
        outcome("kill -9: the engine killed, nothing leaves; hev killed, the app's stop leaves no rule, route, device or rp_filter change")
    }

    @Test
    fun theKillSwitchHoldsAfterKill9UntilItIsReleased(): Unit = runBlocking {
        val before = rpFilter()
        val login = SocksLogin("hold-${nonce()}", "pass-${nonce()}")
        val port = freePort()
        startLine(port, login)
        val process = startTunnel(port, login, killSwitch = true)
        assertRulesStand(killSwitch = true)
        val up = fetchPage(ORIGIN, "/hold/up")
        assertTrue(page(up), "the tunnel with the kill switch carries: $up\n${tail()}")

        hevPids(process).forEach { asRoot("kill", "-9", it.toString()) }
        assertTrue(waitUntil(5_000) { !device(LinuxTunController.TUN_NAME) }, "hev killed, the tun is gone")
        val controller = tun!!
        assertTrue(controller.holdAfterTunDeath(wanted = true), "the block is kept after the tunnel's death")
        controller.stop(process)
        assertTrue(controller.holdsTraffic, "a stop while held keeps the block")
        assertTrue(device(LinuxTunController.KILL_SWITCH_DEVICE), "the block's device stands")
        val blocked = fetch(ORIGIN, "/hold/blocked", timeoutMs = 3_000)
        assertTrue(
            !page(blocked) && requests("/hold/blocked").isEmpty(),
            "held, nothing reaches the far side: $blocked"
        )
        val echoes = world("echo").size
        assertTrue(echo(ORIGIN, "l2: held ${nonce()}".toByteArray(), tries = 2) == null, "held, no datagram comes back")
        assertTrue(world("echo").drop(echoes).isEmpty(), "held, no datagram reached the echo")

        controller.endHold()
        controller.stop(null)
        assertClean(before, "the hold released")
        val after = fetchPage(ORIGIN, "/hold/after")
        assertTrue(page(after), "released, the machine's own way is back: $after")
        assertEquals(listOf(OUT4), requests("/hold/after").distinct(), "and it leaves by the way out")
        outcome("the kill switch: after kill -9 of hev nothing leaves until the hold is released; then nothing is left")
    }

    // ---- the parts of a test ----------------------------------------------------

    /** Without a tunnel the user's traffic leaves by the way out, IPv6 too, and the names are asked from there. */
    private suspend fun controls(prefix: String) {
        val v4 = fetchPage(ORIGIN, "$prefix/control")
        assertTrue(page(v4), "with no tunnel the origin answers: $v4")
        assertEquals(listOf(OUT4), requests("$prefix/control").distinct(), "with no tunnel it is reached from $OUT4")
        val v6 = fetchPage(ORIGIN6, "$prefix/control6")
        assertTrue(page(v6), "with no tunnel the origin answers over IPv6: $v6")
        assertEquals(listOf(OUT6), requests("$prefix/control6").distinct(), "over IPv6 from $OUT6")
        val mark = queries(LINE_NAME).size
        val answer = runCatching { ask(MAPDNS, LINE_NAME) }.getOrElse { listOf("no answer: ${it.message}") }
        assertEquals(listOf(ORIGIN), answer, "with no tunnel $MAPDNS is the far side's resolver")
        assertEquals(listOf(OUT4), queries(LINE_NAME).drop(mark).distinct(), "asked from $OUT4")
    }

    private suspend fun startLine(port: Int, login: SocksLogin): HostEngine {
        val engine = HostEngine(host, binary = { Path.of(check.engine) }, sessionParent = sessions)
        this.engine = engine
        val spec = assertNotNull(LinkParser.parse(check.link), "the check's link does not parse")
        val started = engine.line.start(
            LineRequest(
                spec = spec,
                kind = "Vless",
                port = port,
                routing = Routing.Global,
                verboseLogs = false,
                login = login,
                serverResolver = null,
                autoDetectInterface = true,
                bindInterface = null,
                anotherPort = { error("a line of one process asks for no second port") },
            )
        )
        say(started.said)
        assertTrue(
            started.said.endsWith("its sockets bound to $WAY_OUT"),
            "a line beside the tun is bound to the default route's interface: ${started.said}"
        )
        assertTrue(waitForPort(port, started.alive), "the engine's port did not open\n${tail()}")
        return engine
    }

    private suspend fun startTunnel(port: Int, login: SocksLogin, killSwitch: Boolean = false): Process {
        // hev keeps 63 bytes of a script's path and runs what is left of it.
        for (name in listOf("linux-tun-up.sh", "linux-tun-down.sh")) {
            val script = DesktopPaths.appDataDir().resolve(name).toString()
            assertTrue(script.toByteArray().size < 64, "hev would cut the path of $script to 63 bytes")
        }
        val controller = LinuxTunController(addLog = { line -> say(line) })
        tun = controller
        // In front of ghostlane-engine hev hands the datagrams over on its
        // FWD UDP lane: the engine's inbound with a login refuses hev's UDP
        // ASSOCIATE, which names no port.
        val process = controller.start(
            Path.of(check.hev),
            port,
            killSwitch = killSwitch,
            username = login.username,
            password = login.password,
            udp = LinuxTunController.Udp.InTcp
        )
        tunProcess = process
        return process
    }

    /**
     * The scripts' rules, routes and settings are in place. The controller is
     * ready once the IPv4 rule and route are; the rest of the up script may
     * still be running, so it is given a few seconds.
     */
    private suspend fun assertRulesStand(killSwitch: Boolean) {
        waitUntil(5_000) { rulesMissing(killSwitch).isEmpty() }
        val missing = rulesMissing(killSwitch)
        assertTrue(missing.isEmpty(), "the tunnel stands without: ${missing.joinToString("; ")}")
    }

    /** What of the up script's work is not there, a sentence for each. */
    private fun rulesMissing(killSwitch: Boolean): List<String> = buildList {
        val table = LinuxTunController.ROUTE_TABLE
        for (family in listOf("-4", "-6")) {
            val rules = cmd("ip", family, "rule", "show").lines()
            if (rules.none { it.startsWith("10:") && "uidrange 0-0" in it && "lookup main" in it }) {
                add("ip $family: root keeping the main table: $rules")
            }
            if (rules.none { it.startsWith("20:") && "lookup $table" in it }) {
                add("ip $family: the rest looking up $table: $rules")
            }
        }
        val routes4 = routes("-4")
        if (routes4.none { it.startsWith("default dev ${LinuxTunController.TUN_NAME}") }) {
            add("IPv4 going into the tun: $routes4")
        }
        val routes6 = routes("-6")
        if (routes6.none { it.startsWith("blackhole default") }) add("IPv6 claimed and blackholed: $routes6")
        if (killSwitch &&
            routes4.none {
                it.startsWith("default dev ${LinuxTunController.KILL_SWITCH_DEVICE}") &&
                    "metric ${LinuxTunController.KILL_SWITCH_METRIC}" in it
            }
        ) {
            add("the kill switch's route behind the tun's: $routes4")
        }
        val on = rpFilter().filterValues { it != "0" }
        if (on.isNotEmpty()) add("rp_filter off: $on")
    }

    /** What the up script asked of resolved since the [mark]th call, where the machine has resolvectl. */
    private suspend fun assertResolvedTold(mark: Int) {
        if (check.resolvectl == null) {
            outcome("no resolvectl on this machine: the scripts' lines for it were not reached")
            return
        }
        val name = LinuxTunController.TUN_NAME
        val wanted = listOf("dns $name ${LinuxTunController.MAPDNS_ADDRESS}", "domain $name ~.", "default-route $name yes")
        waitUntil(5_000) { resolvectlAsked().drop(mark).containsAll(wanted) }
        val asked = resolvectlAsked().drop(mark)
        assertTrue(asked.containsAll(wanted), "the up script hands the names to the tun's resolver: $asked")
    }

    private fun assertFarSide(path: String, what: String) {
        val seen = requests(path)
        assertTrue(seen.isNotEmpty() && OUT4 !in seen, "$what: the origin saw it from the far side alone: $seen")
    }

    private fun assertDatagramCrosses(what: String) {
        val mark = world("echo").size
        val payload = "l2: $what, a datagram ${nonce()}".toByteArray()
        val back = echo(ORIGIN, payload)
        assertTrue(back != null && back.contentEquals(payload), "$what: a datagram came back: ${back?.let { String(it) }}\n${tail()}")
        val seen = world("echo").drop(mark).map { it[1] }
        assertTrue(seen.isNotEmpty() && OUT4 !in seen, "$what: the echo saw it from the far side alone: $seen")
    }

    /** IPv6 into the tunnel is refused at once and reaches nothing. What it took, for the record. */
    private fun assertIpv6Refused(path: String): String {
        val started = System.nanoTime()
        val got = fetch(ORIGIN6, path, timeoutMs = 5_000)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(!page(got) && ms < 2_000, "IPv6 is refused at once, not carried or left to time out: $got after $ms ms")
        assertTrue(requests(path).isEmpty(), "the refused IPv6 request reached nothing")
        return "$ms ms"
    }

    private fun assertClean(before: Map<String, String>, what: String) {
        val left = leftovers()
        assertTrue(left.isEmpty(), "$what: the tunnel left $left")
        val after = rpFilter()
        val changed = before.filter { (name, value) -> after[name] != null && after[name] != value }
        assertTrue(changed.isEmpty(), "$what: rp_filter is not back: ${changed.map { (name, value) -> "$name $value -> ${after[name]}" }}")
        if (check.resolvectl != null) {
            assertTrue(
                resolvectlAsked().lastOrNull() == "revert ${LinuxTunController.TUN_NAME}",
                "$what: the cleanup gave the tun's resolver settings back: ${resolvectlAsked().takeLast(4)}"
            )
        }
    }

    /** A session of both halves, as F4c runs it: the core's SOCKS inbound demands the login hev is given. */
    private fun frontSession(port: Int, login: SocksLogin, device: String): String {
        val core = buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }
            putJsonObject("dns") {
                put("queryStrategy", "UseIPv4")
                putJsonArray("servers") {
                    addJsonObject {
                        put("tag", "dns-direct")
                        put("address", WORLD_DNS)
                        put("port", 53)
                    }
                }
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "in")
                    put("protocol", "socks")
                    put("listen", "127.0.0.1")
                    put("port", port)
                    putJsonObject("settings") {
                        put("auth", "password")
                        putJsonArray("accounts") {
                            addJsonObject {
                                put("user", login.username)
                                put("pass", login.password)
                            }
                        }
                        put("udp", true)
                    }
                }
            }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("tag", "room")
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        put("address", "127.0.0.1")
                        put("port", 1080)
                    }
                }
                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                }
            }
            putJsonObject("routing") {
                put("domainStrategy", "AsIs")
                putJsonArray("rules") {
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("inboundTag") { add("dns-direct") }
                        put("outboundTag", "direct")
                    }
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("domain") { add("domain:$DIRECT_NAME") }
                        put("outboundTag", "direct")
                    }
                }
            }
        }
        val room = JsonObject(check.room + ("device_id" to JsonPrimitive(device)))
        return HostSession.text(core.toString(), room.toString(), WAY_OUT)
    }

    // ---- what the namespace says ------------------------------------------------

    /** What [command] printed, stdout and stderr together. */
    private fun cmd(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(20, TimeUnit.SECONDS)
        return out
    }

    private fun asRoot(vararg command: String): String = cmd("sudo", "-n", *command)

    /** The routes of the tun's table for [family], `-4` or `-6`; an empty or missing table has none. */
    private fun routes(family: String): List<String> =
        cmd("ip", family, "route", "show", "table", LinuxTunController.ROUTE_TABLE).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Error") && !it.startsWith("Dump terminated") }

    /** What of a tunnel is in the namespace: rules, routes of its table, its devices. Empty when nothing is. */
    private fun leftovers(): List<String> = buildList {
        for (family in listOf("-4", "-6")) {
            cmd("ip", family, "rule", "show").lines()
                .filter { "lookup ${LinuxTunController.ROUTE_TABLE}" in it || "uidrange 0-0" in it }
                .forEach { add("ip $family rule: ${it.trim()}") }
            routes(family).forEach { add("ip $family route in ${LinuxTunController.ROUTE_TABLE}: $it") }
        }
        for (name in listOf(LinuxTunController.TUN_NAME, LinuxTunController.KILL_SWITCH_DEVICE)) {
            if (device(name)) add("device $name")
        }
    }

    private fun device(name: String): Boolean = File("/sys/class/net/$name").exists()

    /** Every interface's rp_filter but the tunnel's own devices'. */
    private fun rpFilter(): Map<String, String> =
        File("/proc/sys/net/ipv4/conf").listFiles().orEmpty()
            .filter { it.name != LinuxTunController.TUN_NAME && it.name != LinuxTunController.KILL_SWITCH_DEVICE }
            .associate { it.name to File(it, "rp_filter").readText().trim() }

    private fun resolvectlAsked(): List<String> =
        check.resolvectl?.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    private fun uidOf(pid: Long): Int? = runCatching {
        File("/proc/$pid/status").readLines().first { it.startsWith("Uid:") }.trim().split(Regex("\\s+"))[1].toInt()
    }.getOrNull()

    /** hev, under the sudo that started it. */
    private fun hevPids(process: Process): List<Long> =
        process.toHandle().descendants()
            .filter { handle ->
                val commandLine = runCatching { String(Files.readAllBytes(Path.of("/proc/${handle.pid()}/cmdline"))) }
                commandLine.getOrDefault("").startsWith(check.hev)
            }
            .map { it.pid() }
            .toList()

    // ---- what the far side says -------------------------------------------------

    /** The lines of the far side's [service]'s log, each split into its words. */
    private fun world(service: String): List<List<String>> =
        File(check.logs, "world-$service.log").takeIf { it.exists() }?.readLines().orEmpty()
            .filter { it.isNotBlank() && it != "ready" }
            .map { it.split(' ') }

    /** The addresses the origin was asked for [path] from, over IPv4 and IPv6. */
    private fun requests(path: String): List<String> =
        (world("origin") + world("origin6")).filter { it.size >= 3 && it[0] == "request" && it[2] == path }.map { it[1] }

    /** The addresses the far side's resolver was asked for [name] from. */
    private fun queries(name: String): List<String> =
        world("dns").filter { it.size >= 3 && it[0] == "query" && it[2] == name }.map { it[1] }

    /** Where the line's server was asked to go by connections that came to it from [OUT4]. */
    private fun lineCarried(): List<String> {
        val accepted = Regex("from (?:tcp:|udp:)?${Regex.escape(OUT4)}:\\d+ accepted (?:tcp:|udp:)?(\\S+):\\d+")
        return check.xrayAccess.readLines().mapNotNull { accepted.find(it)?.groupValues?.get(1) }
    }

    // ---- what the user's programs do --------------------------------------------

    private fun get(address: String, path: String, name: String, timeoutMs: Int): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(address), 80), timeoutMs)
            socket.soTimeout = timeoutMs
            val request = "GET $path HTTP/1.1\r\nHost: $name\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    /** [get]'s answer, or why there is none, in a sentence. */
    private fun fetch(address: String, path: String, name: String = address, timeoutMs: Int = 8_000): String =
        runCatching { get(address, path, name, timeoutMs) }.getOrElse { "${it::class.simpleName}: ${it.message}" }

    /** [fetch] until the page comes, for a tunnel or a line that has only just come up. */
    private suspend fun fetchPage(address: String, path: String, name: String = address, withinMs: Long = 15_000): String {
        val deadline = System.currentTimeMillis() + withinMs
        while (true) {
            val got = fetch(address, path, name)
            if (page(got) || System.currentTimeMillis() > deadline) return got
            delay(500)
        }
    }

    private fun page(response: String): Boolean = response.startsWith("HTTP/1.1 200") && response.endsWith(BODY)

    /** The address hev's mapdns answers for [name]: one of its own, in 100.64.0.0/10. */
    private fun mapped(name: String): String {
        val answer = runCatching { ask(MAPDNS, name) }.getOrElse { error -> fail("hev's mapdns did not answer for $name: ${error.message}") }
        val octets = answer.singleOrNull()?.split('.')?.map { it.toInt() }
        assertTrue(
            octets != null && octets[0] == 100 && octets[1] in 64..127,
            "hev's mapdns answered $name with an address of its own: $answer"
        )
        return answer.single()
    }

    /** The IPv4 addresses [server] gives for [name], asked by datagram, as a resolver asks. */
    private fun ask(server: String, name: String, timeoutMs: Int = 3_000): List<String> {
        val id = (System.nanoTime() and 0xffff).toInt()
        val query = ByteArrayOutputStream().apply {
            write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in name.split('.')) {
                write(label.length)
                write(label.toByteArray(Charsets.US_ASCII))
            }
            write(byteArrayOf(0, 0, 1, 0, 1))
        }.toByteArray()
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buffer = ByteArray(1500)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return addresses(buffer.copyOf(packet.length), query.size)
        }
    }

    /** The A records of [reply], whose question ends at [questionEnd]. */
    private fun addresses(reply: ByteArray, questionEnd: Int): List<String> {
        fun u16(at: Int) = ((reply[at].toInt() and 0xff) shl 8) or (reply[at + 1].toInt() and 0xff)
        val found = mutableListOf<String>()
        var at = questionEnd
        repeat(u16(6)) {
            while (at < reply.size) {
                val length = reply[at].toInt() and 0xff
                if ((length and 0xc0) == 0xc0) {
                    at += 2
                    break
                }
                at += 1 + length
                if (length == 0) break
            }
            if (at + 10 > reply.size) return found
            val type = u16(at)
            val size = u16(at + 8)
            at += 10
            if (type == 1 && size == 4 && at + 4 <= reply.size) {
                found += (0 until 4).joinToString(".") { (reply[at + it].toInt() and 0xff).toString() }
            }
            at += size
        }
        return found
    }

    /** [payload] to the echo at [address], tried [tries] times: what came back, or null. */
    private fun echo(address: String, payload: ByteArray, tries: Int = 5): ByteArray? {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2_000
            val to = InetSocketAddress(InetAddress.getByName(address), ECHO_PORT)
            repeat(tries) {
                socket.send(DatagramPacket(payload, payload.size, to))
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    return buffer.copyOf(packet.length)
                } catch (_: SocketTimeoutException) {
                    // Not yet: the next try.
                }
            }
        }
        return null
    }

    // ---- small things -----------------------------------------------------------

    private fun freePort(): Int = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private suspend fun waitForPort(port: Int, alive: () -> Boolean, withinMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < deadline) {
            if (!alive()) return false
            val open = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) } }.isSuccess
            if (open) return true
            delay(100)
        }
        return false
    }

    private suspend fun waitUntil(withinMs: Long, what: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < deadline) {
            if (what()) return true
            delay(100)
        }
        return what()
    }

    private fun nonce(): String = (System.nanoTime() % 1_000_000_007L).toString(36)

    private fun say(line: String) {
        log += line
        println(line)
    }

    private fun tail(): String = synchronized(log) { log.takeLast(40).joinToString("\n") }

    private fun outcome(line: String) {
        check.outcomes.appendText("$line\n")
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private companion object {
        const val CHECK_ENV = "LINUX_TUN_CHECK"

        /** The client namespace's way out and its addresses (linux-tun.sh makes them). */
        const val WAY_OUT = "out0"
        const val OUT4 = "10.231.0.2"
        const val OUT6 = "fd00:231::2"

        /** The far side's origin (HTTP on 80, a UDP echo on [ECHO_PORT]) and its resolver. */
        const val ORIGIN = "10.232.0.30"
        const val ORIGIN6 = "fd00:232::30"
        const val ECHO_PORT = 7
        const val WORLD_DNS = "10.232.0.53"
        const val BODY = "the world says hello\n"

        /** The resolver the tun names, which hev answers itself; without a tunnel the far side answers there too. */
        const val MAPDNS = LinuxTunController.MAPDNS_ADDRESS

        /** Names the far side's resolver knows, all of them the origin. */
        const val LINE_NAME = "line.test"
        const val ROOM_NAME = "room.test"
        const val DIRECT_NAME = "direct.test"

        /** How long a room is given to be joined. */
        const val ROOM_WAIT_MS = 90_000L
    }
}
