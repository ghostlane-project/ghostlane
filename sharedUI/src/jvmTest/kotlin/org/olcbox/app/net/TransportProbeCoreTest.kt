package org.olcbox.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smart connect's probe through the Rust core ([TransportProbe.RustStarter]),
 * on a fake starter: what the core is handed, that the probe asks through the
 * port and with the login the core was told, that a line the core would
 * refuse is never started, and that the core is stopped whatever happens.
 * The real core starts behind the same seam on the desktop and on Android.
 */
class TransportProbeCoreTest {
    private val realityLink = IosCoreFixtures.links.getValue(IosCoreLine.RealityVision)
    private val namedRealityLink = realityLink.replace("@127.0.0.1:", "@vpn.example.com:")
    private fun location(link: String) = LocationConfig(name = "probe", kind = LocationKind.Vless, rawLink = link)

    /** What the fake was asked to start, and what became of the core it started. */
    private class Started(val spec: OutboundSpec, val config: String, val bindInterface: String?)

    /**
     * A fake of the Rust core. It reads the one inbound of the core.json it
     * is handed and, when [serves], listens there as a SOCKS5 server that
     * takes the accounts written in it and nothing else.
     */
    private class FakeRustCore(
        private val serves: Boolean = true,
        private val address: String? = null,
    ) : TransportProbe.RustStarter {
        val started = mutableListOf<Started>()
        val refusals = mutableListOf<String>()
        val stops = AtomicInteger()

        /** Each name the probe asked of the starter, with the resolver it was to be asked of. */
        val asked = mutableListOf<Pair<String, DirectDns?>>()

        override suspend fun start(spec: OutboundSpec, config: String, bindInterface: String?): TransportProbe.Core {
            started += Started(spec, config, bindInterface)
            val inbound = Json.parseToJsonElement(config).jsonObject["inbounds"]!!.jsonArray.single().jsonObject
            val account = inbound["settings"]!!.jsonObject["accounts"]!!.jsonArray.single().jsonObject
            val server = if (serves) {
                SocksLoginServer(
                    inbound["listen"]!!.jsonPrimitive.content,
                    inbound["port"]!!.jsonPrimitive.int,
                    account["user"]!!.jsonPrimitive.content,
                    account["pass"]!!.jsonPrimitive.content,
                )
            } else {
                null
            }
            return object : TransportProbe.Core {
                override fun isRunning(): Boolean = server != null && !server.closed
                override suspend fun stop() {
                    stops.incrementAndGet()
                    server?.close()
                }
            }
        }

        override suspend fun serverAddress(host: String, resolver: DirectDns?): String? {
            asked += host to resolver
            return address
        }

        override fun refused(spec: OutboundSpec, why: String) {
            refusals += why
        }
    }

    /** RFC 1928's greeting and RFC 1929's login, answered for one pair: what the core's inbound demands of a client. */
    private class SocksLoginServer(listen: String, port: Int, private val user: String, private val pass: String) {
        private val socket = ServerSocket(port, 8, InetAddress.getByName(listen))
        @Volatile var closed = false
            private set

        init {
            thread(isDaemon = true, name = "fake-rust-core") {
                while (!closed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use { runCatching { answer(it) } }
                }
            }
        }

        private fun answer(client: Socket) {
            client.soTimeout = 2_000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            check(input.readUnsignedByte() == 5)
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (2.toByte() !in methods) {
                output.write(byteArrayOf(5, 0xFF.toByte()))
                return
            }
            output.write(byteArrayOf(5, 2))
            check(input.readUnsignedByte() == 1)
            val name = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            val word = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            output.write(byteArrayOf(1, (if (name == user && word == pass) 0 else 1).toByte()))
            output.flush()
        }

        fun close() {
            closed = true
            socket.close()
        }
    }

    /** The probe's question in place of TransportCheck, which needs the internet: does the port take the login it was given? */
    private val loginTaken: suspend (SubscriptionFetchProxy) -> Boolean = { proxy ->
        Socket(proxy.host, proxy.port).use { socket ->
            socket.soTimeout = 2_000
            val output = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            output.write(byteArrayOf(5, 1, 2))
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) return@use false
            val user = proxy.username.encodeToByteArray()
            val pass = proxy.password.encodeToByteArray()
            output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
            input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0
        }
    }

    /** The resolver a probe is handed unless a test says otherwise: a network's own, as Android's and the desktop's failovers hand theirs. */
    private val underneath = DirectDns.Servers(listOf("192.168.1.1"))

    private fun probe(
        starter: TransportProbe.Starter,
        link: String = realityLink,
        bindInterface: String? = null,
        resolver: DirectDns? = underneath,
        ask: suspend (SubscriptionFetchProxy) -> Boolean = loginTaken,
    ): Boolean = runBlocking {
        withTimeout(20_000) {
            TransportProbe.passesWith(
                location(link),
                serverResolver = resolver,
                autoDetectInterface = bindInterface != null,
                bindInterface = bindInterface,
                starter = starter,
                ask = ask,
            )
        }
    }

    /** What a fake is expected to have been asked, typed as it keeps it. */
    private fun asks(vararg names: Pair<String, DirectDns?>): List<Pair<String, DirectDns?>> = names.toList()

    private fun inbound(config: String): JsonObject =
        Json.parseToJsonElement(config).jsonObject["inbounds"]!!.jsonArray.single().jsonObject

    /** The login and port a config's one inbound was written with. */
    private fun listener(config: String): Pair<Int, SocksLogin> {
        val inbound = inbound(config)
        val account = inbound["settings"]!!.jsonObject["accounts"]!!.jsonArray.single().jsonObject
        return inbound["port"]!!.jsonPrimitive.int to
            SocksLogin(account["user"]!!.jsonPrimitive.content, account["pass"]!!.jsonPrimitive.content)
    }

    // The core is handed its own config for the line, the session's writer's
    // on the probe's port with the probe's login, and the probe asks through
    // that port with that login. Then the core is stopped.
    @Test fun theRustCoreIsHandedItsOwnConfigAndAskedThroughItsPort() {
        val core = FakeRustCore()
        assertTrue(probe(core))
        val start = core.started.single()
        val (port, login) = listener(start.config)
        assertEquals(TransportProbe.rustConfig(LinkParser.parse(realityLink)!!, port, login), start.config)
        assertEquals("127.0.0.1", inbound(start.config)["listen"]!!.jsonPrimitive.content)
        assertEquals("password", inbound(start.config)["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
        assertNull(start.bindInterface)
        assertEquals(1, core.stops.get())
        assertTrue(core.refusals.isEmpty())
    }

    // Each probe makes a login of its own.
    @Test fun everyProbeHasALoginOfItsOwn() {
        val core = FakeRustCore()
        assertTrue(probe(core))
        assertTrue(probe(core))
        val (first, second) = core.started.map { listener(it.config).second }
        assertTrue(first.username != second.username && first.password != second.password)
    }

    // Beside the Linux tun the interface goes to the starter, which tells the
    // engine; the core's config is the same as anywhere else.
    @Test fun besideATunTheInterfaceIsTheStartersAndNotTheConfigs() {
        val core = FakeRustCore()
        assertTrue(probe(core, bindInterface = "eth0"))
        val start = core.started.single()
        assertEquals("eth0", start.bindInterface)
        assertFalse("eth0" in start.config)
        assertFalse("auto_detect_interface" in start.config)
        val (port, login) = listener(start.config)
        assertEquals(TransportProbe.rustConfig(LinkParser.parse(realityLink)!!, port, login), start.config)
    }

    // A server named by a hostname is asked of the starter and written with
    // the address it gives; the line's names stay as the link has them.
    @Test fun aServerNamedByAHostnameIsPinnedToTheAddressTheStarterGives() {
        val core = FakeRustCore(address = "203.0.113.7")
        assertTrue(probe(core, link = namedRealityLink))
        assertEquals(asks("vpn.example.com" to underneath), core.asked)
        val start = core.started.single()
        val pinned = CoreLines.pinHost(LinkParser.parse(namedRealityLink)!!, "203.0.113.7")
        val (port, login) = listener(start.config)
        assertEquals(TransportProbe.rustConfig(pinned, port, login), start.config)
        assertEquals("203.0.113.7", start.spec.host)
        assertFalse("vpn.example.com" in start.config)
        assertTrue("www.microsoft.com" in start.config)
    }

    // The name is asked of the resolver the caller handed the probe, the
    // network's underneath, which the starter cannot find for itself: it is
    // the engine's, and the resolvers are a session's or a failover's. The
    // system's is behind the tun by then. A probe handed none asks with none.
    @Test fun aServersNameIsAskedOfTheResolverTheProbeWasHanded() {
        val wifi = DirectDns.Servers(listOf("10.0.0.1", "fe80::1%wlan0"))
        val core = FakeRustCore(address = "203.0.113.7")
        assertTrue(probe(core, link = namedRealityLink, resolver = wifi))
        assertTrue(probe(core, link = namedRealityLink, resolver = DirectDns.System))
        assertTrue(probe(core, link = namedRealityLink, resolver = null))
        assertEquals(
            asks("vpn.example.com" to wifi, "vpn.example.com" to DirectDns.System, "vpn.example.com" to null),
            core.asked,
        )
        // An address is not a name: nothing is asked for it, whatever the resolver.
        assertTrue(probe(core, resolver = wifi))
        assertEquals(3, core.asked.size)
    }

    // The probe's two public doors hand the resolver on as passesWith does:
    // Android's failover calls the first, the desktop's the second. A core
    // that never opens its port keeps the question to the internet away.
    @Test fun bothPublicProbesHandTheResolverToTheStarter() = runBlocking {
        val wifi = DirectDns.Servers(listOf("10.0.0.1"))
        val android = FakeRustCore(serves = false, address = "203.0.113.7")
        assertFalse(withTimeout(20_000) { TransportProbe.passes(location(namedRealityLink), wifi, android) })
        assertEquals(asks("vpn.example.com" to wifi), android.asked)
        assertEquals(1, android.stops.get())
        val desktop = FakeRustCore(serves = false, address = "203.0.113.7")
        val passed = withTimeout(20_000) {
            TransportProbe.passes(
                location(namedRealityLink),
                serverResolver = wifi,
                autoDetectInterface = true,
                bindInterface = "eth0",
                starter = desktop,
            )
        }
        assertFalse(passed)
        assertEquals(asks("vpn.example.com" to wifi), desktop.asked)
        assertEquals("eth0", desktop.started.single().bindInterface)
        assertEquals(1, desktop.stops.get())
    }

    // A line the core would refuse is never started: the starter is told why
    // and the probe does not pass. A hostname with no address to give is one.
    @Test fun aLineTheRustCoreWouldRefuseIsNeverStarted() {
        val spec = LinkParser.parse(namedRealityLink)!!
        assertEquals(CoreRefusal.HOSTNAME, CoreRefusal.of(spec))
        val core = FakeRustCore(address = null)
        assertFalse(probe(core, link = namedRealityLink))
        assertTrue(core.started.isEmpty())
        assertEquals(listOf(CoreRefusal.HOSTNAME), core.refusals)
        assertEquals(0, core.stops.get())
        // An address that is no address is no pin.
        val lying = FakeRustCore(address = "not-an-address")
        assertFalse(probe(lying, link = namedRealityLink))
        assertTrue(lying.started.isEmpty())
        assertEquals(listOf(CoreRefusal.HOSTNAME), lying.refusals)
    }

    @Test fun aCoreThatDiesBeforeItsPortOpensFailsAndIsStopped() {
        val core = FakeRustCore(serves = false)
        assertFalse(probe(core))
        assertEquals(1, core.started.size)
        assertEquals(1, core.stops.get())
    }

    @Test fun aLineThatFailsTheQuestionFailsAndIsStopped() {
        val core = FakeRustCore()
        assertFalse(probe(core, ask = { false }))
        assertEquals(1, core.stops.get())
        // A login the core was not told is refused by it: the question above is a real one.
        val wrong = FakeRustCore()
        assertFalse(probe(wrong, ask = { proxy -> loginTaken(proxy.copy(password = proxy.password + "x")) }))
        assertEquals(1, wrong.stops.get())
    }

    @Test fun aProbeThatIsCancelledStillStopsItsCore() = runBlocking {
        val core = FakeRustCore()
        val asking = CompletableDeferred<Unit>()
        val probing = async {
            TransportProbe.passesWith(
                location(realityLink), null, false, null, core,
            ) { asking.complete(Unit); awaitCancellation() }
        }
        withTimeout(20_000) { asking.await() }
        probing.cancel()
        runCatching { probing.await() }
        assertEquals(1, core.stops.get())
        assertTrue(probing.isCancelled)
    }

    // No other core is in the app: a starter that is not the Rust core's is
    // never handed a line, and the probe does not pass.
    @Test fun aStarterThatIsNotTheRustCoresIsNeverStarted() {
        var asked = 0
        val other = TransportProbe.Starter { _, _ ->
            asked++
            object : TransportProbe.Core {
                override fun isRunning(): Boolean = false
                override suspend fun stop() {}
            }
        }
        assertFalse(probe(other))
        assertEquals(0, asked)
    }
}
