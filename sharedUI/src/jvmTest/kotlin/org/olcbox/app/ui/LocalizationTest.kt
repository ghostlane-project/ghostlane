package org.olcbox.app.ui

import kotlinx.coroutines.runBlocking
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.locations_count
import multiplatform_app.sharedui.generated.resources.routing_bypass_russia_note
import multiplatform_app.sharedui.generated.resources.settings_title
import multiplatform_app.sharedui.generated.resources.vpn_disclosure_body
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import multiplatform_app.sharedui.generated.resources.allStringResources
import org.olcbox.app.data.repository.SubscriptionRefreshError
import org.olcbox.app.data.repository.SubscriptionRefreshFailure
import org.olcbox.app.data.repository.SubscriptionRefreshReport
import org.olcbox.app.desktop.DESKTOP_MODE_TEXTS
import org.olcbox.app.ui.components.DISCLOSURE_BODY
import org.olcbox.app.ui.components.kit.BoardWords
import org.olcbox.app.ui.features.home.NOTICES
import org.olcbox.app.ui.features.home.StatusWords
import org.olcbox.app.ui.features.home.TransportMismatch
import org.olcbox.app.ui.features.home.localizedBulkMessage
import org.olcbox.app.ui.features.home.localizedSingleMessage
import org.olcbox.app.ui.features.home.components.ListWords
import org.olcbox.app.ui.components.disclosureBlocks
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The strings the UI reads from resources: that they come out as written (an
 * escaped apostrophe is an apostrophe), in Russian on a Russian system with
 * Russian plural forms, and that no English string lacks its Russian one.
 */
class LocalizationTest {
    private val original = Locale.getDefault()

    @AfterTest fun restore() = Locale.setDefault(original)

    private fun <T> inLocale(tag: String, block: suspend () -> T): T {
        Locale.setDefault(Locale.forLanguageTag(tag))
        return runBlocking { block() }
    }

    @Test fun englishComesOutAsWritten() {
        assertEquals("Settings", inLocale("en") { getString(Res.string.settings_title) })
        val note = inLocale("en") { getString(Res.string.routing_bypass_russia_note) }
        assertTrue(note.contains("v2fly's category-ru"), note)
        assertFalse(note.contains("\\"), note)
    }

    @Test fun russianOnARussianSystem() {
        assertEquals("Настройки", inLocale("ru") { getString(Res.string.settings_title) })
    }

    @Test fun russianPluralFormsAreRussian() {
        val forms = inLocale("ru") {
            listOf(1, 2, 5, 11, 21, 22).map { getPluralString(Res.plurals.locations_count, it, it) }
        }
        assertEquals(listOf("1 локация", "2 локации", "5 локаций", "11 локаций", "21 локация", "22 локации"), forms)
    }

    // The notice Play requires is tested as DISCLOSURE_BODY and the sheet shows the
    // resource, so the English resource is that text, character for character.
    @Test fun theDisclosureShownIsTheOneTested() {
        assertEquals(DISCLOSURE_BODY, inLocale("en") { getString(Res.string.vpn_disclosure_body) })
    }

    // Every translation of the notice keeps every section and paragraph of a required
    // notice, and none of the words the English one may not use (see DisclosureBlocksTest):
    // a platform, or "subscription" in that language.
    @Test fun everyDisclosureKeepsEverySectionAndSellsNothing() {
        fun shape(body: String) = disclosureBlocks(body).map { (it.heading != null) to it.paragraphs.size }
        for ((tag, subscription) in listOf("ru" to "подписк", "zh" to "订阅", "fa" to "اشتراک")) {
            val body = inLocale(tag) { getString(Res.string.vpn_disclosure_body) }
            assertEquals(shape(DISCLOSURE_BODY), shape(body), tag)
            listOf("android", "ios", "iphone", "ipad", subscription).forEach { word ->
                assertFalse(word in body.lowercase(), "the $tag disclosure must not say \"$word\"")
            }
        }
    }

    @Test fun chineseAndPersianOnTheirSystems() {
        assertEquals("设置", inLocale("zh-CN") { getString(Res.string.settings_title) })
        assertEquals("设置", inLocale("zh-TW") { getString(Res.string.settings_title) })
        assertEquals("تنظیمات", inLocale("fa") { getString(Res.string.settings_title) })
        assertEquals("5 个节点", inLocale("zh") { getPluralString(Res.plurals.locations_count, 5, 5) })
        assertEquals("1 مکان", inLocale("fa") { getPluralString(Res.plurals.locations_count, 1, 1) })
    }

    // The pure helpers are tested with their English defaults and the screens show
    // the resources: each default is its English resource, word for word.
    @Test fun theHelpersEnglishDefaultsAreTheEnglishResources() {
        val board = BoardWords()
        val list = ListWords()
        val status = StatusWords()
        val defaults = mapOf(
            "wire_unknown" to board.wireUnknown,
            "wire_olcrtc" to board.wireOlcrtc,
            "wire_hysteria2" to board.wireHysteria2,
            "wire_xhttp" to board.wireXhttp,
            "wire_grpc" to board.wireGrpc,
            "wire_reality" to board.wireReality,
            "wire_https" to board.wireHttps,
            "wire_stream" to board.wireStream,
            "wire_stream_no_handshake" to board.wireStreamNoHandshake,
            "ping_ms" to board.pingMs,
            "no_ping" to board.noPing,
            "seats_full" to board.seatsFull,
            "seats_free" to board.seatsFree,
            "onboarding_add_list_caps" to board.addServerList,
            "action_cancel_caps" to board.cancel,
            "action_leave_caps" to board.leave,
            "action_disconnect_caps" to board.disconnect,
            "action_room_full_caps" to board.roomFull,
            "action_take_seat_in_caps" to board.takeSeatIn,
            "action_take_seat_caps" to board.takeSeat,
            "action_connect_via_caps" to board.connectVia,
            "action_connect_caps" to board.connect,
            "board_rooms" to board.rooms,
            "board_servers" to board.servers,
            "sort_as_served_caps" to board.sortAsServed,
            "sort_ping_caps" to board.sortPing,
            "sort_az_caps" to board.sortAlphabetical,
            "sub_server_list" to list.serverList,
            "list_encrypted" to list.encryptedList,
            "quota_used" to list.used,
            "quota_available" to list.available,
            "list_expires" to list.expires,
            "list_updated" to list.updated,
            "age_now" to list.now,
            "age_minutes" to list.minutes,
            "age_hours" to list.hours,
            "age_days" to list.days,
            "plan_traffic" to list.traffic,
            "plan_resets_today" to list.trafficResetsToday,
            "plan_resets_in" to list.trafficResetsIn,
            "status_in_room" to status.inRoom,
            "status_connected" to status.connected,
            "status_joining_room" to status.joiningRoom,
            "status_connecting" to status.connecting,
            "status_no_server_list" to status.noServerList,
            "status_not_connected" to status.notConnected,
            "status_add_list_to_start" to status.addServerListToStart,
            "status_room_full" to status.roomFull,
            "mismatch_caption_caps" to TransportMismatch.CAPTION,
            "mismatch_explanation" to TransportMismatch.EXPLANATION
        )
        val english = inLocale("en") { defaults.keys.associateWith { getString(Res.allStringResources.getValue(it)) } }
        assertEquals(defaults, english)
    }

    // The olcRTC failures are compared as English sentences and shown translated: each
    // one's English resource is the sentence itself, so English reads as it did.
    @Test fun eachClassifiedFailureIsItsOwnEnglishResource() {
        val english = inLocale("en") { NOTICES.mapValues { getString(it.value) } }
        assertEquals(NOTICES.keys.associateWith { it }, english)
    }

    // The desktop's mode texts are English in the preference and translated on screen:
    // each one's English resource is the text itself.
    @Test fun eachDesktopModeTextIsItsOwnEnglishResource() {
        val english = inLocale("en") { DESKTOP_MODE_TEXTS.mapValues { getString(it.value) } }
        assertEquals(DESKTOP_MODE_TEXTS.keys.associateWith { it }, english)
    }

    // The refresh outcomes the screens show say, in English, what the report says.
    @Test fun theLocalizedRefreshMessagesAreTheReportsOwnEnglish() {
        fun failure(error: SubscriptionRefreshError, code: Int? = null) = SubscriptionRefreshFailure("https://x", error, code)
        val reports = listOf(
            SubscriptionRefreshReport(),
            SubscriptionRefreshReport(updatedCount = 2),
            SubscriptionRefreshReport(failures = listOf(failure(SubscriptionRefreshError.Rejected, 403))),
            SubscriptionRefreshReport(failures = listOf(failure(SubscriptionRefreshError.ServerError), failure(SubscriptionRefreshError.Unreachable))),
            SubscriptionRefreshReport(updatedCount = 1, failures = listOf(failure(SubscriptionRefreshError.Empty)))
        )
        inLocale("en") {
            reports.forEach { report ->
                assertEquals(report.singleMessage(), report.localizedSingleMessage())
                assertEquals(report.bulkMessage(), report.localizedBulkMessage())
            }
        }
    }

    // A string added in English only would show English inside a translated screen.
    @Test fun everyEnglishStringHasEveryTranslation() {
        fun keys(path: String): Set<String> =
            Regex("""<(?:string|plurals) name="([^"]+)"""").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()
        val english = keys("src/commonMain/composeResources/values/strings.xml")
        for (tag in listOf("ru", "zh", "fa")) {
            val translated = keys("src/commonMain/composeResources/values-$tag/strings.xml")
            assertEquals(emptySet(), english - translated, "missing in values-$tag")
            assertEquals(emptySet(), translated - english, "only in values-$tag")
        }
    }
}
