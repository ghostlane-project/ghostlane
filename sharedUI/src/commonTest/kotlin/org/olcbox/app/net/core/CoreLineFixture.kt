package org.olcbox.app.net.core

import org.olcbox.app.net.IosCoreFixtures
import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayGeodata
import org.olcbox.app.net.core.index.coreFixtureIndex

/**
 * One share link a kind of line is held to. The writer's config for it, under
 * Global and under Bypass Russia with the stand-in lists, is a reference
 * config the core's own parser is asked about: `<reference>.json` and
 * `bypass-<reference>.json` under jvmTest `reference-configs`.
 */
class CoreCase(
    /** The case's name, one of a kind among every kind's cases. */
    val name: String,
    /** The link, with a loopback address and made-up keys. */
    val link: String,
    /**
     * The reference config's name without `.json`: `ios-core-<name>` for the
     * case named after a shape, which is that line of the iPhone's
     * ([IosCoreLine]: every shape of every kind), and whose name the
     * iPhone's tests and the Mac host read; `core-<name>` for every other.
     */
    val reference: String = if (IosCoreLine.of(name) != null) "ios-core-$name" else "core-$name",
) {
    /** The name of the reference under Bypass Russia. */
    val bypassReference: String get() = "bypass-$reference"

    fun spec(): OutboundSpec = requireNotNull(LinkParser.parse(link)) { "the fixture case $name does not parse" }

    companion object {
        /**
         * The case of one of the six lines the iPhone ships: its link is the
         * iPhone's own fixture ([IosCoreFixtures.links]) and its references
         * keep the names the iPhone's tests read (`ios-core-<slug>.json`).
         */
        fun shipped(line: IosCoreLine): CoreCase =
            CoreCase(line.slug, IosCoreFixtures.links.getValue(line), reference = "ios-core-${line.slug}")
    }
}

/**
 * A kind's fixture: the links it is held to, at least one for each shape the
 * kind writes. It is an object in a file `<Kind>Fixture.kt` under commonTest
 * `net/core/lines`, and that file is its registration: the build lists the
 * fixtures from the directory ([CoreFixtures.all]). A kind with no fixture
 * fails [CoreRegistry].
 */
interface CoreLineFixture {
    val line: CoreLine
    val cases: List<CoreCase>
}

/** What every kind's fixture is written with, and all of them together. */
object CoreFixtures {
    /** A Reality public key, made up. */
    const val PUBLIC_KEY = IosCoreFixtures.PUBLIC_KEY

    /** A SHA-256 in OpenSSL's spelling, as `pinSHA256` usually comes. */
    const val PIN = IosCoreFixtures.PIN

    /** The pair the reference configs are written with; the app generates its own. */
    val login: SocksLogin get() = IosCoreFixtures.login

    /** Stand-ins for the Russian lists, so that a Bypass Russia reference stays small ([IosCoreFixtures.lists]). */
    val lists: XrayGeodata.Lists get() = IosCoreFixtures.lists

    /** Every kind's fixture. */
    val all: List<CoreLineFixture> get() = coreFixtureIndex

    /** Every case of every kind. */
    val cases: List<CoreCase> get() = all.flatMap { it.cases }

    /** The case called [name]. */
    fun case(name: String): CoreCase = cases.single { it.name == name }
}
