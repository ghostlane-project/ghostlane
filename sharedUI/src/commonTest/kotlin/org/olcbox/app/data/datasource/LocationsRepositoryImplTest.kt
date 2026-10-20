package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.crypt.CryptCodec
import org.olcbox.app.data.device.DeviceDescription
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.model.DeviceRefusal
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.RefusedList
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.data.share.ConfigShareService
import io.ktor.http.HttpStatusCode
import org.olcbox.app.net.PartnerLinkResolver
import org.olcbox.app.net.PartnerLinkResult
import org.olcbox.app.data.repository.DEVICE_LIMIT_REACHED_MESSAGE
import org.olcbox.app.data.repository.DEVICE_NOT_SUPPORTED_MESSAGE
import org.olcbox.app.data.repository.SubscriptionRefreshError
import org.olcbox.app.data.repository.SubscriptionRefreshReport
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationsRepositoryImplTest {
    @Test fun cancellingARefreshPreservesTheListAndPropagatesCancellation() = runTest {
        var cancel = false
        var now = 1_000L
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(source, HttpClient(MockEngine {
            if (cancel) throw kotlinx.coroutines.CancellationException("settings changed")
            respond("olcrtc://wbstream?vp8channel@first#${"c".repeat(64)}${'$'}First")
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/list"))
        val before = repo.getBundle()
        now += 86_400_000L
        cancel = true
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { repo.refreshDueSubscriptions() }
        assertEquals(before, repo.getBundle())
    }

    @Test fun dueRefreshHonorsUserIntervalAddsServersAndPreservesSelection() = runTest {
        var now = 1_000L
        var body = "olcrtc://wbstream?vp8channel@first#${"c".repeat(64)}${'$'}First"
        var requests = 0
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(source, HttpClient(MockEngine {
            requests++
            respond(body, headers = headersOf("profile-update-interval", "24"))
        }), nowEpochMs = { now })
        assertTrue(repo.importText("https://example.test/list"))
        val selected = repo.getActiveLocationId()
        repo.saveSubscriptionSettings(org.olcbox.app.data.model.SubscriptionSettings(updateIntervalHours = 1))
        now += 3_600_000L
        body += "\n" + "olcrtc://wbstream?vp8channel@second#${"d".repeat(64)}${'$'}Second"
        assertEquals(1, repo.refreshDueSubscriptions().updatedCount)
        assertEquals(2, repo.getAllLocations().size)
        assertEquals(selected, repo.getActiveLocationId())
        assertEquals(0, repo.refreshDueSubscriptions().updatedCount)
        repo.saveSubscriptionSettings(org.olcbox.app.data.model.SubscriptionSettings(autoUpdate = false))
        now += 86_400_000L
        assertEquals(0, repo.refreshDueSubscriptions().updatedCount)
        assertEquals(2, requests)
    }



    @Test
    fun exportsAndImportsBundleV5WithActiveLocation() = runTest {
        val first = LocationEntry.from(
            "amsterdam",
            LocationConfig("Amsterdam", "room-a", "key-a", LocationConfig.PROVIDER_SALUTEJAZZ)
        )
        val second = LocationEntry.from(
            "berlin",
            LocationConfig("Berlin", "room-b", "key-b", LocationConfig.PROVIDER_TELEMOST)
        )
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "berlin",
                locations = listOf(first, second)
            )
        )
        val exported = LocationsRepositoryImpl(source).exportBundle()
        assertTrue("\"version\": 5" in exported)
        assertTrue("\"endpoint\"" in exported)
        assertTrue("\"auth_provider\"" in exported)
        assertTrue("\"client_id\"" !in exported)
        assertTrue("\"bypass_provider\"" !in exported)
        val importedSource = FakeLocationsDataSource()

        LocationsRepositoryImpl(importedSource).importText(exported)

        val imported = importedSource.stored
        assertNotNull(imported)
        assertEquals(5, imported.version)
        assertEquals("berlin", imported.activeLocationId)
        assertEquals(listOf("amsterdam", "berlin"), imported.locations.map { it.storageId })
        assertEquals(
            LocationConfig.PROVIDER_TELEMOST,
            imported.locations[1].location.bypassProvider
        )
    }

    @Test
    fun migratesLegacyLocationsAndPreservesActiveSelection() = runTest {
        val source = FakeLocationsDataSource(
            legacy = listOf(
                "legacy_a" to """{"name":"A","server":"room-a","password":"key-a","provider":"jazz"}""",
                "legacy_b" to """{"name":"B","server":"room-b","password":"key-b","turn":{"type":"wbstream"}}"""
            ),
            legacyActive = "legacy_b"
        )
        val bundle = LocationsRepositoryImpl(source).getBundle()

        assertEquals("legacy_b", bundle.activeLocationId)
        assertEquals(listOf("legacy_a", "legacy_b"), bundle.locations.map { it.storageId })
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, bundle.locations[1].location.bypassProvider)
        // "jazz" is SaluteJazz under upstream's old name: a location stored
        // under it lands on the carrier the engine runs today.
        assertEquals(LocationConfig.PROVIDER_SALUTEJAZZ, bundle.locations[0].location.bypassProvider)
        assertEquals(bundle, source.stored)
    }

    @Test
    fun normalizesStoredWbStreamAliasToCanonicalProvider() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "wb",
                locations = listOf(
                    LocationEntry(
                        storageId = "wb",
                        name = "WB",
                        legacyId = "room-wb",
                        legacyKey = "key-wb",
                        legacyBypassProvider = "wbstream"
                    )
                )
            )
        )

        val active = LocationsRepositoryImpl(source).getActiveLocation()

        assertNotNull(active)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, active.bypassProvider)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, active.location.bypassProvider)
    }

    @Test
    fun importsWbStreamAliasAsCanonicalProvider() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 3,
              "active_location_id": "wb",
              "locations": [
                {
                  "storage_id": "wb",
                  "name": "WB",
                  "id": "room-wb",
                  "key": "key-wb",
                  "bypass_provider": "wbstream"
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, imported.locations.first().bypassProvider)
    }

    @Test
    fun importsSingleLegacyLocationWithTurnProvider() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "hysteria": {
                "name": "Paris",
                "server": "room-paris",
                "password": "key-paris"
              },
              "turn": {
                "type": "telemost"
              }
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals("imported_paris", imported.activeLocationId)
        assertEquals(1, imported.locations.size)
        assertEquals("room-paris", imported.locations.first().location.id)
        assertEquals(
            LocationConfig.PROVIDER_TELEMOST,
            imported.locations.first().location.bypassProvider
        )
    }

    @Test
    fun importsLegacyOlcRtcUriWithClientIdAndMimoName() = runTest {
        val source = FakeLocationsDataSource()
        val input = "olcrtc://wbstream?seichannel@room-01#${"a".repeat(64)}%android-01${'$'}RU / olc free sub / IPv6"

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val entry = imported.locations.single()
        val location = entry.location
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_SEICHANNEL, location.transport)
        assertEquals("room-01", location.id)
        assertEquals("RU / olc free sub / IPv6", location.name)
        assertEquals("RU / olc free sub / IPv6", entry.metadata?.mimo)
        assertNull(entry.metadata?.subscription)
    }

    // The third carrier (Sber SaluteJazz, LiveKit-as-JSON over pion), merged
    // into the engine 2026-09-22. The room is a `<code>:<password>` pair
    // carried whole in the URI's room segment - unlike wbstream/telemost it
    // has no separate splitting of its own, so the parser must not touch it.
    @Test
    fun importsSalutejazzOlcRtcUri() = runTest {
        val source = FakeLocationsDataSource()
        val key = "e".repeat(64)
        val input = "olcrtc://salutejazz?datachannel@zzz999:pw123456#$key${'$'}DE · olcRTC · SJ"

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.single().location
        assertEquals(LocationConfig.PROVIDER_SALUTEJAZZ, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, location.transport)
        assertEquals("zzz999:pw123456", location.id)
        assertEquals(key, location.key)
        assertEquals("DE · olcRTC · SJ", location.name)
    }

    // The fifth carrier (VK Calls, olcrtc#52). The room is the call's whole
    // join link, `://` and path included, carried in the room segment as a
    // Jitsi link is: the engine takes the link, not an id cut out of it.
    @Test
    fun importsVkcallsOlcRtcUri() = runTest {
        val source = FakeLocationsDataSource()
        val key = "f".repeat(64)
        val input = "olcrtc://vkcalls?vp8channel@https://vk.ru/call/join/AbC-12_xyz#$key${'$'}My VK room"

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.single().location
        assertEquals(LocationConfig.PROVIDER_VKCALLS, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, location.transport)
        assertEquals("https://vk.ru/call/join/AbC-12_xyz", location.id)
        assertEquals(key, location.key)
        assertEquals("My VK room", location.name)
    }

    // The sixth carrier (MTS Link, olcrtc#57). The room is a permanent
    // meeting's whole join link, two path segments past `/j/`, carried in the
    // room segment as it is: the engine takes the link.
    @Test
    fun importsMtslinkOlcRtcUri() = runTest {
        val source = FakeLocationsDataSource()
        val key = "f".repeat(64)
        val input = "olcrtc://mtslink?vp8channel@https://my.mts-link.ru/j/100200300/20300400500#$key${'$'}DE · olcRTC · MTS"

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.single().location
        assertEquals(LocationConfig.PROVIDER_MTSLINK, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, location.transport)
        assertEquals("https://my.mts-link.ru/j/100200300/20300400500", location.id)
        assertEquals(key, location.key)
        assertEquals("DE · olcRTC · MTS", location.name)
    }

    // The engine carries a Jitsi room over DataChannel only, so the app runs
    // one over DataChannel whatever the link says - and used to say nothing
    // about it. A server set up for vp8channel then waited for a peer that
    // never spoke its transport (olcbox#15). The request is kept on the entry,
    // so the board and the connect button can say what happened.
    @Test
    fun keepsTheTransportALinkAskedForWhenTheProviderCannotCarryIt() = runTest {
        val source = FakeLocationsDataSource()
        val key = "b".repeat(64)
        val input = "olcrtc://jitsi?vp8channel<vp8-fps=25&vp8-batch=1>@https://meet.egovm.ru/olcrtc-x#$key"

        LocationsRepositoryImpl(source).importText(input)

        val entry = assertNotNull(source.stored).locations.single()
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, entry.location.transport)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, entry.metadata?.requestedTransport)
    }

    @Test
    fun aTransportTheProviderCarriesIsNotARequest() = runTest {
        val source = FakeLocationsDataSource()
        val key = "b".repeat(64)
        val input = listOf(
            "olcrtc://telemost?vp8channel@12345#$key",
            "olcrtc://jitsi?datachannel@https://meet.egovm.ru/olcrtc-y#$key"
        ).joinToString("\n")

        LocationsRepositoryImpl(source).importText(input)

        val entries = assertNotNull(source.stored).locations
        assertEquals(2, entries.size)
        entries.forEach { entry ->
            assertNull(entry.metadata?.requestedTransport, "${entry.location.bypassProvider} asked for nothing it cannot have")
        }
    }

    @Test
    fun importsJitsiOlcRtcUriWithRoomUrl() = runTest {
        val source = FakeLocationsDataSource()
        val key = "b".repeat(64)
        val input = "olcrtc://jitsi?datachannel@https://meet.cryptopro.ru/myroom#$key${'$'}Jitsi room"

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.single().location
        assertEquals(LocationConfig.PROVIDER_JITSI, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, location.transport)
        assertEquals("https://meet.cryptopro.ru/myroom", location.id)
        assertEquals("Jitsi room", location.name)
    }

    @Test
    fun importsOlcRtcSubscriptionAndAppliesLocalNames() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            #name: Test subscription
            #update: 1778011200
            #refresh: 10m
            #color: #4A90E2
            #icon: flag-ru
            #used: 10mb/10gb
            #available: 9.99gb

            olcrtc://wbstream?seichannel@room-01#${"a".repeat(64)}%android-01${'$'}RU / default name
            ##name: RU-1
            ##color: #4A90E2
            ##icon: node-ru
            ##used: 500mb/10gb
            ##available: 9.5gb
            ##ip: 203.0.113.10
            ##comment: primary

            olcrtc://jazz?datachannel@room-02#${"b".repeat(64)}%android-02${'$'}DE / backup
            ##name: DE-Backup
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("RU-1", "DE-Backup"), imported.locations.map { it.location.name })
        assertEquals(
            listOf(LocationConfig.PROVIDER_WB_STREAM, LocationConfig.PROVIDER_SALUTEJAZZ),
            imported.locations.map { it.location.bypassProvider }
        )
        assertEquals("imported_ru-1", imported.activeLocationId)

        val firstMetadata = imported.locations[0].metadata
        assertNotNull(firstMetadata)
        assertEquals("RU-1", firstMetadata.name)
        assertEquals("#4A90E2", firstMetadata.color)
        assertEquals("node-ru", firstMetadata.icon)
        assertEquals("500mb/10gb", firstMetadata.used)
        assertEquals("9.5gb", firstMetadata.available)
        assertEquals("203.0.113.10", firstMetadata.ip)
        assertEquals("primary", firstMetadata.comment)
        assertEquals("RU / default name", firstMetadata.mimo)

        val subscriptionMetadata = firstMetadata.subscription
        assertNotNull(subscriptionMetadata)
        assertEquals("Test subscription", subscriptionMetadata.name)
        assertEquals("1778011200", subscriptionMetadata.update)
        assertEquals("10m", subscriptionMetadata.refresh)
        assertEquals("#4A90E2", subscriptionMetadata.color)
        assertEquals("flag-ru", subscriptionMetadata.icon)
        assertEquals("10mb/10gb", subscriptionMetadata.used)
        assertEquals("9.99gb", subscriptionMetadata.available)
        assertEquals(subscriptionMetadata, imported.locations[1].metadata?.subscription)
    }

    fun importUpdatesMatchingStorageIdsAndAppendsNewLocations() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "custom_paris",
                locations = listOf(
                    LocationEntry.from(
                        "custom_paris",
                        LocationConfig(
                            name = "Paris",
                            id = "room-old",
                            key = "a".repeat(64),
                            bypassProvider = LocationConfig.PROVIDER_WB_STREAM
                        )
                    ),
                    LocationEntry.from(
                        "custom_berlin",
                        LocationConfig(
                            name = "Berlin",
                            id = "room-berlin",
                            key = "b".repeat(64),
                            bypassProvider = LocationConfig.PROVIDER_TELEMOST
                        )
                    )
                )
            )
        )
        val input = """
            {
              "version": 4,
              "active_location_id": "sub_wb",
              "locations": [
                {
                  "storage_id": "custom_paris",
                  "name": "Paris updated",
                  "endpoint": {
                    "room_id": "room-new",
                    "key": "${"c".repeat(64)}",
                    "client_id": "phone-1"
                  },
                  "carrier": "wbstream",
                  "transport": {
                    "type": "datachannel"
                  }
                },
                {
                  "storage_id": "sub_wb",
                  "name": "WB sub",
                  "subscription_url": "https://example.com/sub.md",
                  "endpoint": {
                    "room_id": "room-sub",
                    "key": "${"d".repeat(64)}",
                    "client_id": "phone-2"
                  },
                  "carrier": "wbstream",
                  "transport": {
                    "type": "vp8channel"
                  }
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("custom_paris", "custom_berlin", "sub_wb"), imported.locations.map { it.storageId })
        assertEquals("room-new", imported.locations[0].location.id)
        assertEquals("room-berlin", imported.locations[1].location.id)
        assertEquals("https://example.com/sub.md", imported.locations[2].subscriptionUrl)
        assertEquals("sub_wb", imported.activeLocationId)
    }

    @Test
    fun invalidLocationCannotBecomeActiveLocation() = runTest {
        val source = FakeLocationsDataSource()
        val incomplete = LocationConfig(name = "Broken", id = "room", key = "")

        LocationsRepositoryImpl(source).saveLocation("broken", incomplete)

        val bundle = source.stored
        assertNotNull(bundle)
        assertNull(bundle.activeLocationId)
        assertTrue(bundle.locations.isEmpty())
    }

    @Test
    fun telemostLocationsForceVp8AndOtherProvidersCanUseDatachannel() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 3,
              "locations": [
                {
                  "storage_id": "telemost",
                  "name": "Telemost",
                  "id": "75047680642749",
                  "key": "${"a".repeat(64)}",
                  "bypass_provider": "telemost",
                  "transport": "datachannel"
                },
                {
                  "storage_id": "wb",
                  "name": "WB",
                  "id": "room-wb",
                  "key": "${"b".repeat(64)}",
                  "bypass_provider": "wbstream",
                  "transport": "datachannel"
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, imported.locations[0].location.transport)
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, imported.locations[1].location.transport)
    }

    @Test
    fun importsUnsupportedVideochannelAsDefaultTransport() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 4,
              "active_location_id": "telemost-video",
              "locations": [
                {
                  "storage_id": "telemost-video",
                  "name": "Telemost Video",
                  "endpoint": {
                    "room_id": "75047680642749",
                    "key": "${"c".repeat(64)}"
                  },
                  "carrier": "telemost",
                  "transport": {
                    "type": "videochannel"
                  }
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.first().location
        assertEquals(5, imported.version)
        assertEquals(LocationConfig.PROVIDER_TELEMOST, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, location.transport)
    }

    @Test
    fun exposesAllWorkingProviderTransportPairs() {
        assertEquals(
            listOf(
                LocationConfig.TRANSPORT_VP8CHANNEL,
                LocationConfig.TRANSPORT_SEICHANNEL
            ),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_TELEMOST)
        )
        assertEquals(
            listOf(
                LocationConfig.TRANSPORT_DATACHANNEL,
                LocationConfig.TRANSPORT_VP8CHANNEL,
                LocationConfig.TRANSPORT_SEICHANNEL
            ),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_WB_STREAM)
        )
        assertEquals(
            listOf(LocationConfig.TRANSPORT_DATACHANNEL),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_JITSI)
        )
        assertEquals(
            LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.normalizeTransport(LocationConfig.TRANSPORT_VP8CHANNEL, LocationConfig.PROVIDER_JITSI)
        )
        // SaluteJazz guests get data channels only - no media track - so a
        // link that asked for vp8/sei still lands the room on datachannel.
        assertEquals(
            listOf(LocationConfig.TRANSPORT_DATACHANNEL),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_SALUTEJAZZ)
        )
        assertEquals(
            LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.normalizeTransport(LocationConfig.TRANSPORT_VP8CHANNEL, LocationConfig.PROVIDER_SALUTEJAZZ)
        )
    }

    @Test
    fun olcRtcSingleProfileImportDoesNotOverwriteExistingStorageId() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "imported_room-01",
                locations = listOf(
                    LocationEntry.from(
                        "imported_room-01",
                        LocationConfig("Old", "room-old", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
                    )
                )
            )
        )

        LocationsRepositoryImpl(source).importText(
            "olcrtc://wbstream?seichannel@room-01#${"b".repeat(64)}${'$'}New"
        )

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("imported_room-01", "imported_new"), imported.locations.map { it.storageId })
        assertEquals("room-old", imported.locations[0].location.id)
        assertEquals("room-01", imported.locations[1].location.id)
        assertEquals("imported_new", imported.activeLocationId)
    }

    @Test
    fun bundleRestoreUpdatesMatchingStorageIds() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "same",
                locations = listOf(
                    LocationEntry.from(
                        "same",
                        LocationConfig("Old", "room-old", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
                    )
                )
            )
        )

        val input = """
            {
              "version": 4,
              "active_location_id": "same",
              "locations": [
                {
                  "storage_id": "same",
                  "name": "Updated",
                  "endpoint": {
                    "room_id": "room-new",
                    "key": "${"b".repeat(64)}",
                    "client_id": "desktop"
                  },
                  "carrier": "wbstream",
                  "transport": {"type": "datachannel"}
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("same"), imported.locations.map { it.storageId })
        assertEquals("room-new", imported.locations.single().location.id)
    }

    @Test
    fun subscriptionHeaderSetsIntervalAndIdentityHeaders() = runTest {
        var userAgent: String? = null
        var hwid: String? = null
        val engine = MockEngine { request ->
            userAgent = request.headers[HttpHeaders.UserAgent]
            hwid = request.headers["x-hwid"]
            respond(
                content = "olcrtc://wbstream?vp8channel@room#${"c".repeat(64)}${'$'}Sub",
                headers = headersOf("profile-update-interval", "6")
            )
        }
        val source = FakeLocationsDataSource()

        LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).importText("https://example.test/sub.txt")

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(CurrentAppInfo.userAgent, userAgent)
        assertEquals("hwid-test", hwid)
        assertEquals(6, imported.locations.single().metadata?.subscription?.updateIntervalHours)
        assertEquals("https://example.test/sub.txt", imported.locations.single().subscriptionUrl)
    }

    // A provider that holds a list to a number of devices counts them by the
    // id and shows the three device headers beside each one, as Happ sends them.
    @Test
    fun subscriptionIdentityRequestNamesTheDevice() = runTest {
        var sent: Headers? = null
        val engine = MockEngine { request ->
            sent = request.headers
            respond("olcrtc://wbstream?vp8channel@room#${"c".repeat(64)}${'$'}Sub")
        }

        assertTrue(
            LocationsRepositoryImpl(
                dataSource = FakeLocationsDataSource(),
                httpClient = HttpClient(engine),
                deviceIdentityProvider = StaticIdentityProvider("hwid-test"),
                describeDevice = { DeviceDescription(os = "Android", osVersion = "16", model = "Pixel 9 Pro") }
            ).importText("https://example.test/sub.txt")
        )

        val headers = assertNotNull(sent)
        assertEquals("hwid-test", headers["x-hwid"])
        assertEquals("Android", headers["x-device-os"])
        assertEquals("16", headers["x-ver-os"])
        assertEquals("Pixel 9 Pro", headers["x-device-model"])
        val names = headers.names()
        for (name in listOf("x-hwid", "x-device-os", "x-ver-os", "x-device-model")) {
            assertTrue(name in names, "$name is spelled in lower case on the wire: $names")
        }
    }

    // What the platform says may be anything; a header carries what the server
    // keeps of it, and a value with nothing printable in it is not sent at all.
    @Test
    fun subscriptionDeviceHeadersAreCutAndAnEmptyOneIsNotSent() = runTest {
        var sent: Headers? = null
        val engine = MockEngine { request ->
            sent = request.headers
            respond("olcrtc://wbstream?vp8channel@room#${"c".repeat(64)}${'$'}Sub")
        }
        val model = "iPhone17,1 ✓" + "x".repeat(70)

        LocationsRepositoryImpl(
            dataSource = FakeLocationsDataSource(),
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test"),
            describeDevice = { DeviceDescription(os = "iOS", osVersion = " \u0007 é ", model = model) }
        ).importText("https://example.test/sub.txt")

        val headers = assertNotNull(sent)
        assertEquals("iOS", headers["x-device-os"])
        assertNull(headers["x-ver-os"], "nothing printable left: no header, not an empty one")
        assertEquals("iPhone17,1 " + "x".repeat(53), headers["x-device-model"])
    }

    // Without a stand-in the request names the machine the test runs on, within
    // the same bounds: the platform's own description is what production sends.
    @Test
    fun subscriptionIdentityRequestNamesThePlatformItRunsOn() = runTest {
        var sent: Headers? = null
        val engine = MockEngine { request ->
            sent = request.headers
            respond("olcrtc://wbstream?vp8channel@room#${"c".repeat(64)}${'$'}Sub")
        }

        LocationsRepositoryImpl(
            dataSource = FakeLocationsDataSource(),
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).importText("https://example.test/sub.txt")

        val headers = assertNotNull(sent)
        assertTrue(
            headers["x-device-os"] in setOf("Android", "iOS", "macOS", "Windows", "Linux"),
            "x-device-os: ${headers["x-device-os"]}"
        )
        for (name in listOf("x-ver-os", "x-device-model")) {
            val value = assertNotNull(headers[name], "$name is sent")
            assertTrue(value.length in 1..64 && value.all { it in ' '..'~' }, "$name: $value")
        }
    }

    // The stored id cannot be read this time (the file is there, the read
    // fails). The list is asked without an id, never with a new one, which the
    // provider would count as another device for good; the device headers go
    // as always, and nothing is written in place of the id.
    @Test
    fun aDeviceIdThatCannotBeReadIsNotSentAndNotReplaced() = runTest {
        var sent: Headers? = null
        val engine = MockEngine { request ->
            sent = request.headers
            respond("olcrtc://wbstream?vp8channel@room#${"c".repeat(64)}${'$'}Sub")
        }
        var idWrites = 0
        val source = object : LocationsDataSource {
            var stored: LocationBundleV4? = null
            override suspend fun loadLocationBundle(): LocationBundleV4? = stored
            override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
            override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
            override suspend fun loadLegacyActiveLocationId(): String? = null
            override suspend fun loadDeviceIdentity(): String? = throw IllegalStateException("device_identity: read failed")
            override suspend fun saveDeviceIdentity(value: String) { idWrites++ }
        }
        val repository = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            describeDevice = { DeviceDescription(os = "Android", osVersion = "16", model = "Pixel 9 Pro") }
        )

        assertTrue(repository.importText("https://example.test/sub.txt"))

        val headers = assertNotNull(sent)
        assertNull(headers["x-hwid"], "an id went out though the stored one could not be read")
        assertEquals(CurrentAppInfo.userAgent, headers[HttpHeaders.UserAgent])
        assertEquals("Android", headers["x-device-os"])
        assertEquals(0, idWrites, "a new id was stored in place of the one that could not be read")
        assertEquals("", repository.getDeviceIdentity(), "the engine is handed none, not a new one")
    }

    @Test
    fun subscriptionImportFallsBackWhenIdentityResponseIsNotConfig() = runTest {
        val userAgents = mutableListOf<String?>()
        val hwids = mutableListOf<String?>()
        val sent = mutableListOf<Headers>()
        val engine = MockEngine { request ->
            userAgents += request.headers[HttpHeaders.UserAgent]
            hwids += request.headers["x-hwid"]
            sent += request.headers
            if (userAgents.size == 1) {
                respond("<html>blocked</html>")
            } else {
                respond(
                    content = "\uFEFFolcrtc://wbstream?vp8channel@room#${"d".repeat(64)}${'$'}Fallback",
                    headers = headersOf("profile-update-interval", "12")
                )
            }
        }
        val source = FakeLocationsDataSource()

        val imported = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test"),
            describeDevice = { DeviceDescription(os = "Android", osVersion = "16", model = "Pixel 9 Pro") }
        ).importText("http://example.test/sub.txt")

        val bundle = source.stored
        assertTrue(imported)
        assertNotNull(bundle)
        assertEquals(2, userAgents.size)
        assertEquals(CurrentAppInfo.userAgent, userAgents[0])
        assertEquals("hwid-test", hwids[0])
        assertEquals("Android", sent[0]["x-device-os"])
        assertTrue(userAgents[1]?.startsWith("Mozilla/5.0") == true)
        assertNull(hwids[1])
        // The retry stands in for a browser, and a browser names no device.
        for (name in listOf("x-hwid", "x-device-os", "x-ver-os", "x-device-model")) {
            assertNull(sent[1][name], "the Compatibility request carries no $name")
        }
        assertEquals("room", bundle.locations.single().location.id)
        assertEquals(12, bundle.locations.single().metadata?.subscription?.updateIntervalHours)
        assertEquals("http://example.test/sub.txt", bundle.locations.single().subscriptionUrl)
    }

    @Test
    fun refreshSingleSubscriptionPreservesOtherSubscriptions() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "beta",
                locations = listOf(
                    LocationEntry.from(
                        "alpha",
                        LocationConfig("Alpha", "room-alpha", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = "https://example.test/alpha"
                    ),
                    LocationEntry.from(
                        "beta",
                        LocationConfig("Beta", "room-beta", "b".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = "https://example.test/beta"
                    )
                )
            )
        )
        val engine = MockEngine { request ->
            respond("olcrtc://wbstream?vp8channel@room-alpha-new#${"c".repeat(64)}${'$'}Alpha")
        }

        val updated = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).refreshSubscription("https://example.test/alpha")

        val bundle = source.stored
        assertEquals(1, updated.updatedCount)
        assertFalse(updated.hasFailures)
        assertNotNull(bundle)
        assertEquals(listOf("beta", "imported_alpha"), bundle.locations.map { it.storageId })
        assertEquals("room-beta", bundle.locations.first { it.storageId == "beta" }.location.id)
        assertEquals("https://example.test/beta", bundle.locations.first { it.storageId == "beta" }.subscriptionUrl)
        assertEquals("room-alpha-new", bundle.locations.first { it.subscriptionUrl == "https://example.test/alpha" }.location.id)
        assertEquals("beta", bundle.activeLocationId)
    }

    @Test
    fun failedSingleSubscriptionRefreshDoesNotDropExistingSubscription() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "alpha",
                locations = listOf(
                    LocationEntry.from(
                        "alpha",
                        LocationConfig("Alpha", "room-alpha", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = "https://example.test/alpha"
                    )
                )
            )
        )
        val engine = MockEngine {
            respond("<html>not a config</html>")
        }

        val updated = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).refreshSubscription("https://example.test/alpha")

        val bundle = source.stored
        assertEquals(0, updated.updatedCount)
        // Unparseable body ⇒ the user is told the subscription returned nothing,
        // not the old ambiguous "not updated".
        assertEquals(SubscriptionRefreshError.Empty, updated.failures.single().error)
        assertNotNull(bundle)
        assertEquals(listOf("alpha"), bundle.locations.map { it.storageId })
        assertEquals("room-alpha", bundle.locations.single().location.id)
        assertEquals("alpha", bundle.activeLocationId)
    }

    @Test
    fun configShareRoundTripsTransportOptions() = runTest {
        val source = FakeLocationsDataSource()
        val config = LocationConfig(
            name = "Shared",
            id = "room",
            key = "d".repeat(64),
            bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
            transport = LocationConfig.TRANSPORT_VP8CHANNEL,
            vp8Fps = 48,
            vp8Batch = 32
        )

        val shared = ConfigShareService.olcRtcUri(config)
        assertTrue("%" !in shared)

        LocationsRepositoryImpl(source).importText(shared)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(48, imported.locations.single().location.vp8Fps)
        assertEquals(32, imported.locations.single().location.vp8Batch)
    }

    @Test
    fun subscriptionSharingListsDistinctUrls() {
        val first = LocationEntry.from(
            "first",
            LocationConfig("First", "room-a", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/a"
        )
        val second = LocationEntry.from(
            "second",
            LocationConfig("Second", "room-b", "b".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/b"
        )
        val third = LocationEntry.from(
            "third",
            LocationConfig("Third", "room-c", "c".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/a"
        )

        val items = ConfigShareService.subscriptionShareItems(listOf(first, second, third))

        assertEquals(listOf("https://example.test/a", "https://example.test/b"), items.map { it.url })
        assertEquals(2, items.first().locationCount)
        assertEquals("https://example.test/b", ConfigShareService.subscriptionQrText(items[1].url))
    }

    @Test
    fun importsCryptLinkDecryptingToInlineOlcrtc() = runTest {
        // Fixtures from the Rust coordinator crypt_link::tests::print_fixture (key=[42;32]).
        // FIXTURE_LINE_BLOB = encrypt("olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef$DE").
        val keyB64 = "KioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKio="
        val lineBlob =
            "Zqjg1M-z4N-c8Tr6FkkyALBXUMbQOMBnSreruYGLPpucGBUyebLLhNxo8VqV9MXMEjfiDiJm-CB8Z0y2-4DObbUMS4Z_sJElAdEoELy2nP2LThWtmT4zTYTD7RHXyENGI6I4webacShIYERWoNE25A"
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(
            source,
            cryptCodec = CryptCodec(CryptCodec.decodeMaster(keyB64)!!)
        )

        val ok = repo.importText("olcrtc://crypt1/$lineBlob")

        assertTrue(ok)
        val imported = source.stored
        assertNotNull(imported)
        val loc = imported.locations.map { it.location }.first { it.id == "12345" }
        assertEquals("deadbeefdeadbeef", loc.key)
        assertEquals(LocationConfig.PROVIDER_TELEMOST, loc.bypassProvider)
    }

    @Test
    fun plainOlcrtcLinkStillImportsWithCryptCodecPresent() = runTest {
        // Marker-only: a plain link is untouched even when a codec is baked.
        val keyB64 = "KioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKio="
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(
            source,
            cryptCodec = CryptCodec(CryptCodec.decodeMaster(keyB64)!!)
        )

        val ok = repo.importText("olcrtc://telemost?vp8channel@99999#feedfacefeedface\$FI")

        assertTrue(ok)
        val loc = source.stored!!.locations.map { it.location }.first { it.id == "99999" }
        assertEquals("feedfacefeedface", loc.key)
    }

    @Test
    fun oldBundleWithoutKindDeserializesAsOlcrtc() {
        val json = """{"name":"X","id":"room","key":"k","bypass_provider":"telemost","transport":"vp8channel"}"""
        val cfg = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(LocationConfig.serializer(), json)
        assertEquals(org.olcbox.app.net.LocationKind.Olcrtc, cfg.kind)
        assertEquals(null, cfg.rawLink)
        assertTrue(cfg.isComplete())
    }

    @Test
    fun vlessKindNeedsParseableRawLink() {
        val ok = LocationConfig(
            name = "DE",
            kind = org.olcbox.app.net.LocationKind.Vless,
            rawLink = "vless://u@1.2.3.4:443?security=reality&pbk=P&sid=s&sni=x#DE"
        )
        assertTrue(ok.isComplete())
        val bad = ok.copy(rawLink = "garbage")
        assertTrue(!bad.isComplete())
    }

    @Test
    fun importsMixedOlcrtcAndVlessSubscription() = runTest {
        val body = """
            olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef${'$'}DE-rtc
            vless://11111111-1111-1111-1111-111111111111@9.9.9.9:443?security=reality&pbk=P&sid=ab&sni=x&flow=xtls-rprx-vision&type=tcp#DE-vless
            hysteria2://PW@8.8.8.8:443?sni=h#DE-hy2
        """.trimIndent()
        val source = FakeLocationsDataSource()
        LocationsRepositoryImpl(source).importText(body)
        val locs = source.stored!!.locations.map { it.location }
        assertEquals(3, locs.size)
        assertEquals(1, locs.count { it.kind == org.olcbox.app.net.LocationKind.Olcrtc })
        assertEquals(1, locs.count { it.kind == org.olcbox.app.net.LocationKind.Vless })
        assertEquals(1, locs.count { it.kind == org.olcbox.app.net.LocationKind.Hysteria2 })
    }

    // ---- refresh failure reporting ----------------------------------------
    // A revoked token, a dead server and an offline device used to be
    // indistinguishable from "nothing changed", so users read every one of them
    // as the app being broken.

    private fun subscribedSource(url: String) = FakeLocationsDataSource(
        stored = LocationBundleV4(
            activeLocationId = "alpha",
            locations = listOf(
                LocationEntry.from(
                    "alpha",
                    LocationConfig("Alpha", "room-alpha", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                    subscriptionUrl = url
                )
            )
        )
    )

    private fun repoRespondingWith(source: FakeLocationsDataSource, engine: MockEngine) =
        LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        )

    @Test
    fun revokedSubscriptionReportsRejectedWithStatus() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine { respond("gone", HttpStatusCode.NotFound) }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        assertEquals(0, report.updatedCount)
        val failure = report.failures.single()
        assertEquals(SubscriptionRefreshError.Rejected, failure.error)
        assertEquals(404, failure.statusCode)
        assertTrue(failure.message().contains("revoked"))
        // the existing locations must survive a failed refresh
        assertEquals(listOf("alpha"), source.stored!!.locations.map { it.storageId })
    }

    @Test
    fun serverErrorIsReportedSeparatelyFromRejection() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        val failure = report.failures.single()
        assertEquals(SubscriptionRefreshError.ServerError, failure.error)
        assertEquals(500, failure.statusCode)
    }

    @Test
    fun unreachableServerIsReportedAsUnreachable() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine { throw IllegalStateException("no route to host") }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        val failure = report.failures.single()
        assertEquals(SubscriptionRefreshError.Unreachable, failure.error)
        assertNull(failure.statusCode)
    }

    @Test
    fun emptyBodyIsReportedAsEmpty() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine { respond("   ") }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        assertEquals(SubscriptionRefreshError.Empty, report.failures.single().error)
    }

    @Test
    fun successfulRefreshReportsNoFailures() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val engine = MockEngine {
            respond("olcrtc://wbstream?vp8channel@room-alpha-new#${"c".repeat(64)}${'$'}Alpha")
        }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        assertEquals(1, report.updatedCount)
        assertFalse(report.hasFailures)
        assertEquals("Server list updated", report.singleMessage())
    }

    @Test
    fun blankUrlRefreshIsANoOpReport() = runTest {
        val source = subscribedSource("https://example.test/alpha")
        val engine = MockEngine { respond("unused") }

        val report = repoRespondingWith(source, engine).refreshSubscription("   ")

        assertEquals(0, report.updatedCount)
        assertFalse(report.hasFailures)
    }

    // ---- provider headers: keeping users through a blocked domain -----------
    // Spelled as Happ reads them: fallback-url, new-url / new-domain, announce.

    private val listBody = "olcrtc://wbstream?vp8channel@room-alpha-new#${"c".repeat(64)}${'$'}Alpha"

    private fun FakeLocationsDataSource.subscription() = stored!!.locations.single().metadata?.subscription
    private fun FakeLocationsDataSource.listUrl() = stored!!.locations.single().subscriptionUrl

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun anAnnouncementIsKeptDecodedAndCappedUntilTheProviderSendsZero() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val note = "Серверы переехали, обновите подписку"
        val encoded = "base64:" + kotlin.io.encoding.Base64.Default.encode(note.encodeToByteArray())
        var announce: String? = encoded
        var refused = false
        val repo = repoRespondingWith(source, MockEngine {
            val value = announce
            when {
                refused -> refusal(announce = value)
                value == null -> respond(listBody)
                else -> respond(listBody, headers = headersOf("announce", value))
            }
        })

        repo.refreshSubscription(url)
        assertEquals(note, source.subscription()?.announce)

        announce = null
        repo.refreshSubscription(url)
        assertEquals(note, source.subscription()?.announce, "an answer without the header keeps the note")

        announce = "x".repeat(300)
        repo.refreshSubscription(url)
        assertEquals(200, source.subscription()?.announce?.length)

        announce = "0"
        repo.refreshSubscription(url)
        assertNull(source.subscription()?.announce)

        // A refusal says why in the same header, held to the same length: a
        // sentence too long is shown cut, and one that is not the base64 it says
        // it is leaves ours in its place. Neither is a reason to fail.
        refused = true
        announce = "y".repeat(300)
        val long = repo.refreshSubscription(url)
        assertEquals("y".repeat(200), source.stored!!.refusedLists.single().subscription?.announce)
        assertEquals(SubscriptionRefreshError.DeviceRefused("y".repeat(200)), long.failures.single().error)

        announce = "base64:%%% not base64 %%%"
        val garbled = repo.refreshSubscription(url)
        assertNull(source.stored!!.refusedLists.single().subscription?.announce)
        assertEquals(SubscriptionRefreshError.DeviceRefused(""), garbled.failures.single().error)
        assertEquals(DEVICE_LIMIT_REACHED_MESSAGE, garbled.singleMessage())
    }

    @Test
    fun aListThatDoesNotAnswerIsAskedAtItsSpareAddress() = runTest {
        val url = "https://example.test/alpha"
        val spare = "https://spare.example.test/alpha"
        val source = subscribedSource(url)
        var primaryUp = true
        val asked = mutableListOf<String>()
        val repo = repoRespondingWith(source, MockEngine { request ->
            asked += request.url.toString()
            when {
                request.url.host == "example.test" && primaryUp ->
                    respond(listBody, headers = headersOf("fallback-url", spare))
                request.url.host == "example.test" -> throw IllegalStateException("blocked")
                else -> respond(listBody)
            }
        })

        repo.refreshSubscription(url)
        assertEquals(spare, source.subscription()?.fallbackUrl)

        primaryUp = false
        asked.clear()
        val report = repo.refreshSubscription(url)
        assertEquals(1, report.updatedCount)
        assertFalse(report.hasFailures)
        // The list's own address first (asked again in compatibility mode, as any
        // failed fetch is), the spare only after it.
        assertEquals(spare, asked.last())
        assertTrue(asked.dropLast(1).isNotEmpty() && asked.dropLast(1).all { it == url }, asked.toString())
        // The spare answered, but the list is still filed under its own address,
        // and the spare stays known for the next time.
        assertEquals(url, source.listUrl())
        assertEquals(spare, source.subscription()?.fallbackUrl)
    }

    @Test
    fun aListThatMovedIsFiledAtItsNewAddress() = runTest {
        val url = "https://example.test/alpha?token=1"
        val source = subscribedSource(url)
        val repo = repoRespondingWith(source, MockEngine { request ->
            if (request.url.host == "example.test") {
                respond(listBody, headers = headersOf("new-url", "https://new.example.test/beta"))
            } else {
                respond(listBody)
            }
        })

        repo.refreshSubscription(url)
        assertEquals("https://new.example.test/beta", source.listUrl())
    }

    @Test
    fun aNewDomainKeepsTheListsPathAndQuery() = runTest {
        val url = "https://example.test/sub/alpha?token=1"
        val source = subscribedSource(url)
        val repo = repoRespondingWith(source, MockEngine { request ->
            if (request.url.host == "example.test") {
                respond(listBody, headers = headersOf("new-domain", "mirror.example.test"))
            } else {
                respond(listBody)
            }
        })

        repo.refreshSubscription(url)
        assertEquals("https://mirror.example.test/sub/alpha?token=1", source.listUrl())
    }

    // Over plain http anyone on the path could write these headers, and a moved
    // list never comes back by itself.
    @Test
    fun movesAndSparesFromAPlainHttpAnswerAreIgnored() = runTest {
        val url = "http://example.test/alpha"
        val source = subscribedSource(url)
        val repo = repoRespondingWith(source, MockEngine {
            respond(
                listBody,
                headers = headersOf(
                    "new-url" to listOf("https://elsewhere.example.test/alpha"),
                    "fallback-url" to listOf("https://spare.example.test/alpha"),
                    "announce" to listOf("still shown")
                )
            )
        })

        repo.refreshSubscription(url)
        assertEquals(url, source.listUrl())
        assertNull(source.subscription()?.fallbackUrl)
        assertEquals("still shown", source.subscription()?.announce)
    }

    @Test
    fun aMoveToAnythingButHttpsIsIgnored() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val repo = repoRespondingWith(source, MockEngine {
            respond(
                listBody,
                headers = headersOf(
                    "new-url" to listOf("http://downgrade.example.test/alpha"),
                    "fallback-url" to listOf("javascript:alert(1)")
                )
            )
        })

        repo.refreshSubscription(url)
        assertEquals(url, source.listUrl())
        assertNull(source.subscription()?.fallbackUrl)
    }

    // ---- a device refusal ----------------------------------------------------
    // A list's host that holds its list to a number of devices answers one past
    // it, or one it removed, and a provider that lets in only an app naming its
    // device answers one that names none: a 200 that says so in its headers,
    // with the reason in `announce` and one line in place of the list, a server
    // named with that reason, for the apps that read no header (spec §7.4 of the
    // device limit). The answer is final: asked again without the id, a soft
    // provider would serve the list uncounted and a strict one refuse it again.

    private val refusalSentence =
        "Your provider: this link is already in use on its 1 device. Ask your provider to free one."

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun base64Header(text: String) =
        "base64:" + kotlin.io.encoding.Base64.Default.encode(text.encodeToByteArray())

    /** The one line the server writes in place of the list: a server at 127.0.0.1:1 named with the reason. */
    private fun refusalLine(sentence: String = refusalSentence) =
        "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:1?type=tcp&security=none#" +
            sentence.encodeURLParameter()

    /** The refusal as the server sends it, with the headers any answer to a subscriber carries. */
    private fun MockRequestHandleScope.refusal(
        flag: String = "x-hwid-max-devices-reached",
        announce: String? = base64Header(refusalSentence),
        body: String = refusalLine()
    ): HttpResponseData = respond(
        content = body,
        headers = headersOf(
            *listOfNotNull(
                "x-hwid-active" to listOf("true"),
                flag to listOf("true"),
                "x-hwid-limit" to listOf("true"),
                announce?.let { "announce" to listOf(it) },
                "profile-title" to listOf(base64Header("Partner VPN")),
                "support-url" to listOf("https://t.me/partner_support"),
                "subscription-userinfo" to listOf("upload=0; download=1073741824; total=10737418240; expire=0"),
                "profile-update-interval" to listOf("12")
            ).toTypedArray()
        )
    )

    @Test
    fun aDeviceRefusalMakesNoSecondRequest() = runTest {
        val url = "https://example.test/alpha"
        val spare = "https://spare.example.test/alpha"
        // A list served before, with a spare address arranged and a note of the
        // provider's on it: the refusal is the answer, so neither the spare nor
        // a request without the id is asked, and the old note is not the reason.
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "alpha",
                locations = listOf(
                    LocationEntry.from(
                        "alpha",
                        LocationConfig("Alpha", "room-alpha", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = url,
                        metadata = LocationMetadata(
                            subscription = SubscriptionMetadata(fallbackUrl = spare, announce = "Servers moved")
                        )
                    )
                )
            )
        )
        val asked = mutableListOf<Headers>()
        val report = repoRespondingWith(source, MockEngine { request ->
            asked += request.headers
            refusal()
        }).refreshSubscription(url)

        assertEquals(1, asked.size, "asked again after the host refused this device")
        assertEquals("hwid-test", asked.single()["x-hwid"])
        assertEquals(0, report.updatedCount)
        assertEquals(SubscriptionRefreshError.DeviceRefused(refusalSentence), report.failures.single().error)
        assertEquals(refusalSentence, report.singleMessage())
        val stored = source.stored!!
        assertEquals(emptyList(), stored.locations, "a refused list keeps no server")
        // What the list says about itself stays, so the board can say whose
        // list it is, how to reach its provider, and why it is empty.
        val refused = stored.refusedLists.single()
        assertEquals(url, refused.subscriptionUrl)
        assertEquals(refusalSentence, refused.subscription?.announce)
        assertEquals("Partner VPN", refused.subscription?.name)
        assertEquals("https://t.me/partner_support", refused.subscription?.supportUrl)
        assertEquals(spare, refused.subscription?.fallbackUrl)
        assertEquals(12, refused.subscription?.updateIntervalHours)
    }

    @Test
    fun aRefusalsRemarkLineIsNotAServer() = runTest {
        val url = "https://example.test/partner"
        // The line is a server to any reader of lines: without the flags, it is
        // imported as one, named with the reason.
        val reader = FakeLocationsDataSource()
        assertTrue(repoRespondingWith(reader, MockEngine { respond(refusalLine()) }).importText(url))
        assertEquals(listOf(refusalSentence), reader.stored!!.locations.map { it.name })

        // With them it is the answer's way of saying no, and none of it is kept.
        val source = FakeLocationsDataSource()
        var requests = 0
        val outcome = repoRespondingWith(source, MockEngine { requests++; refusal() }).importReport(url)

        assertEquals(1, requests)
        assertFalse(outcome.imported)
        assertEquals(SubscriptionRefreshError.DeviceRefused(refusalSentence), outcome.refused)
        assertEquals(emptyList(), source.stored!!.locations)
        assertNull(source.stored!!.activeLocationId)
        assertEquals(listOf(url), source.stored!!.refusedLists.map { it.subscriptionUrl })
    }

    @Test
    fun anUnsupportedDeviceAnswerIsARefusalToo() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val sentence = "Your provider: this app does not identify the device, or that is switched off in its " +
            "settings. Use Happ, v2RayTun or Ghostlane with the device id on."
        var announce: String? = base64Header(sentence)
        var requests = 0
        val repo = repoRespondingWith(source, MockEngine {
            requests++
            refusal(flag = "x-hwid-not-supported", announce = announce, body = refusalLine(sentence))
        })

        val report = repo.refreshSubscription(url)

        assertEquals(1, requests)
        assertEquals(
            SubscriptionRefreshError.DeviceRefused(sentence, DeviceRefusal.NotSupported),
            report.failures.single().error
        )
        assertEquals(emptyList(), source.stored!!.locations)
        val refused = source.stored!!.refusedLists.single()
        assertEquals(sentence, refused.subscription?.announce)
        assertEquals(DeviceRefusal.NotSupported, refused.refusal)

        // Where the provider gives no sentence, ours for this kind: the device's
        // id went unread, which is not the list's limit.
        announce = null
        assertEquals(DEVICE_NOT_SUPPORTED_MESSAGE, repo.refreshSubscription(url).singleMessage())
    }

    // The kind is kept with the list, so the board can say ours for it later;
    // and read leniently: a kind a later version wrote reads as the limit, where
    // a bundle that failed to read would be every list lost.
    @Test
    fun aRefusalsKindIsKeptAndAKindFromALaterVersionReadsAsTheLimit() {
        val bundle = LocationBundleV4(
            refusedLists = listOf(RefusedList("https://example.test/sub", refusal = DeviceRefusal.NotSupported))
        )
        val written = storedJson.encodeToString(LocationBundleV4.serializer(), bundle)
        assertTrue("\"not_supported\"" in written, written)
        assertEquals(
            DeviceRefusal.NotSupported,
            storedJson.decodeFromString(LocationBundleV4.serializer(), written).refusedLists.single().refusal
        )
        val later = written.replace("\"not_supported\"", "\"removed\"")
        assertEquals(
            DeviceRefusal.LimitReached,
            storedJson.decodeFromString(LocationBundleV4.serializer(), later).refusedLists.single().refusal
        )
    }

    /** Configured as the platforms' data sources read and write the bundle. */
    private val storedJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // The repository's half of a refusal that leaves a running tunnel alone (the
    // tunnel's half: HomeScreenModelImportLinkTest.aRefusalKeepsTheRunningTunnel):
    // the server chosen in another list stays chosen, and that list is served.
    @Test
    fun aRefusalOfOneListLeavesTheServerChosenInAnotherChosen() = runTest {
        val ours = "https://example.test/ours"
        val theirs = "https://partner.example.test/sub"
        var partnerRefuses = false
        val source = FakeLocationsDataSource()
        val repo = repoRespondingWith(source, MockEngine { request ->
            when {
                request.url.host != "partner.example.test" ->
                    respond("olcrtc://wbstream?vp8channel@room-ours#${"a".repeat(64)}${'$'}Ours")
                partnerRefuses -> refusal()
                else -> respond("olcrtc://wbstream?vp8channel@room-theirs#${"b".repeat(64)}${'$'}Theirs")
            }
        })
        assertTrue(repo.importText(ours))
        assertTrue(repo.importText(theirs))
        val chosen = repo.getAllLocations().single { it.subscriptionUrl == ours }.storageId
        repo.setActiveLocationId(chosen)

        partnerRefuses = true
        val report = repo.refreshSubscriptions()

        assertEquals(1, report.updatedCount)
        assertEquals(SubscriptionRefreshError.DeviceRefused(refusalSentence), report.failures.single().error)
        assertFalse(report.refusedSelection)
        assertEquals(chosen, repo.getActiveLocationId(), "a refusal of another list moved the selection")
        assertEquals(listOf(ours), repo.getAllLocations().map { it.subscriptionUrl })
        assertEquals(listOf(theirs), source.stored!!.refusedLists.map { it.subscriptionUrl })
    }

    private val oursUrl = "https://example.test/ours"
    private val theirsUrl = "https://partner.example.test/sub"

    /**
     * Our list and the partner's, a server each, the partner's selected; the
     * partner's host refuses this device once [partnerRefuses] says so.
     */
    private suspend fun theirsSelected(
        source: FakeLocationsDataSource,
        partnerRefuses: () -> Boolean
    ): LocationsRepositoryImpl {
        val repo = repoRespondingWith(source, MockEngine { request ->
            when {
                request.url.host != "partner.example.test" ->
                    respond("olcrtc://wbstream?vp8channel@room-ours#${"a".repeat(64)}${'$'}Ours")
                partnerRefuses() -> refusal()
                else -> respond("olcrtc://wbstream?vp8channel@room-theirs#${"b".repeat(64)}${'$'}Theirs")
            }
        })
        assertTrue(repo.importText(oursUrl))
        assertTrue(repo.importText(theirsUrl))
        repo.setActiveLocationId(repo.getAllLocations().single { it.subscriptionUrl == theirsUrl }.storageId)
        return repo
    }

    // The selected server was the refused list's own. It goes with the rest,
    // and nothing is selected in its place: the first remaining server is
    // another list's, and a platform's next start of a running tunnel would
    // carry the session onto it unasked. The report says so, and the store
    // keeps no selection until the person chooses one: read again, and
    // through a refresh of the other list.
    @Test
    fun aRefusalOfTheSelectedServersListLeavesNoSelection() = runTest {
        var partnerRefuses = false
        val source = FakeLocationsDataSource()
        val repo = theirsSelected(source) { partnerRefuses }
        val ours = repo.getAllLocations().filter { it.subscriptionUrl == oursUrl }

        partnerRefuses = true
        val report = repo.refreshSubscription(theirsUrl)

        assertTrue(report.refusedSelection)
        assertNull(repo.getActiveLocationId(), "the selection went to a server nobody chose")
        assertNull(repo.getActiveLocation())
        assertEquals(ours, repo.getAllLocations(), "the other list was touched")
        assertEquals(listOf(theirsUrl), source.stored!!.refusedLists.map { it.subscriptionUrl })
        assertTrue(source.stored!!.selectionWithdrawn)

        assertNull(LocationsRepositoryImpl(source).getActiveLocationId(), "read again, a server was chosen")
        assertEquals(1, repo.refreshSubscription(oursUrl).updatedCount)
        assertNull(repo.getActiveLocationId(), "a refresh of the other list chose a server")

        // Until the person chooses.
        repo.setActiveLocationId(ours.single().storageId)
        assertEquals(ours.single().storageId, repo.getActiveLocationId())
        assertFalse(source.stored!!.selectionWithdrawn)
    }

    // The same when the list in use is pasted again and its host refuses it.
    @Test
    fun aRefusedPasteOfTheListInUseLeavesNoSelection() = runTest {
        var partnerRefuses = false
        val source = FakeLocationsDataSource()
        val repo = theirsSelected(source) { partnerRefuses }

        partnerRefuses = true
        assertNotNull(repo.importReport(theirsUrl).refused)

        assertNull(repo.getActiveLocationId())
        assertEquals(listOf(oursUrl), repo.getAllLocations().map { it.subscriptionUrl })
    }

    // With the selection withdrawn, a refresh that brings lines the store has
    // not seen chooses none of them. A line with no previous entry matched
    // "the selected entry's new id" when there was no selection at all, and
    // the last new line of any list was chosen unasked.
    @Test
    fun aWithdrawnSelectionSurvivesRefreshesThatBringNewLines() = runTest {
        var oursBody = "olcrtc://wbstream?vp8channel@room-ours#${"a".repeat(64)}${'$'}Ours"
        var partnerRefuses = false
        val source = FakeLocationsDataSource()
        val repo = repoRespondingWith(source, MockEngine { request ->
            when {
                request.url.host != "partner.example.test" -> respond(oursBody)
                partnerRefuses -> refusal()
                else -> respond(
                    "olcrtc://wbstream?vp8channel@room-theirs#${"b".repeat(64)}${'$'}Theirs\n" +
                        "olcrtc://wbstream?vp8channel@room-theirs-2#${"b".repeat(64)}${'$'}Theirs 2"
                )
            }
        })
        assertTrue(repo.importText(oursUrl))
        assertTrue(repo.importText(theirsUrl))
        repo.setActiveLocationId(repo.getAllLocations().first { it.subscriptionUrl == theirsUrl }.storageId)
        partnerRefuses = true
        assertTrue(repo.refreshSubscription(theirsUrl).refusedSelection)
        assertNull(repo.getActiveLocationId())

        // (a) Our list again, its room moved and a line added: both new to the store.
        oursBody = "olcrtc://wbstream?vp8channel@room-ours-moved#${"a".repeat(64)}${'$'}Ours\n" +
            "olcrtc://wbstream?vp8channel@room-ours-2#${"a".repeat(64)}${'$'}Ours 2"
        assertEquals(1, repo.refreshSubscription(oursUrl).updatedCount)
        assertEquals(listOf("room-ours-moved", "room-ours-2"), repo.getAllLocations().map { it.location.id })
        assertNull(repo.getActiveLocationId(), "a new line of the other list was chosen")
        assertTrue(source.stored!!.selectionWithdrawn)

        // (b) The refused list served again: every line of it is new.
        partnerRefuses = false
        assertEquals(1, repo.refreshSubscription(theirsUrl).updatedCount)
        assertEquals(4, repo.getAllLocations().size)
        assertEquals(emptyList(), source.stored!!.refusedLists)
        assertNull(repo.getActiveLocationId(), "a line of the list served again was chosen")
        assertTrue(source.stored!!.selectionWithdrawn)
    }

    // The store's only list refused leaves nothing to choose from, and so
    // nothing withdrawn to keep. Served again, its first line is chosen, as a
    // list imported into an empty store has it: not the last, which "the
    // selected entry's new id" matching every new line used to choose, nor
    // the one chosen before the refusal, whose entry the refusal took away.
    @Test
    fun theOnlyListServedAgainAfterARefusalHasItsFirstLineChosen() = runTest {
        var refuses = false
        val url = "https://example.test/alpha"
        val source = FakeLocationsDataSource()
        val repo = repoRespondingWith(source, MockEngine {
            if (refuses) {
                refusal()
            } else {
                respond(
                    "olcrtc://wbstream?vp8channel@room-1#${"c".repeat(64)}${'$'}First\n" +
                        "olcrtc://wbstream?vp8channel@room-2#${"c".repeat(64)}${'$'}Second\n" +
                        "olcrtc://wbstream?vp8channel@room-3#${"c".repeat(64)}${'$'}Third"
                )
            }
        })
        assertTrue(repo.importText(url))
        repo.setActiveLocationId(repo.getAllLocations().last().storageId)
        refuses = true
        assertTrue(repo.refreshSubscription(url).refusedSelection)
        assertNull(repo.getActiveLocationId())
        assertFalse(source.stored!!.selectionWithdrawn, "with no location left, nothing is withdrawn")

        refuses = false
        assertEquals(1, repo.refreshSubscription(url).updatedCount)

        val lines = repo.getAllLocations()
        assertEquals(listOf("room-1", "room-2", "room-3"), lines.map { it.location.id })
        assertEquals(lines.first().storageId, repo.getActiveLocationId())
    }

    @Test
    fun aWithdrawnSelectionIsKeptUntilALocationIsChosen() {
        val entry = subEntry("b1", "https://proofkit.org/sub/bbb")
        val withdrawn = LocationBundleV4(locations = listOf(entry), selectionWithdrawn = true).normalized()
        assertNull(withdrawn.activeLocationId)
        assertTrue(withdrawn.selectionWithdrawn)
        // Without it a missing selection is the first location, as it always was.
        assertEquals("b1", LocationBundleV4(locations = listOf(entry)).normalized().activeLocationId)
        // A choice ends it.
        val chosen = withdrawn.copy(activeLocationId = "b1").normalized()
        assertEquals("b1", chosen.activeLocationId)
        assertFalse(chosen.selectionWithdrawn)
        // With no location left to choose it means nothing, and goes.
        assertFalse(LocationBundleV4(selectionWithdrawn = true).normalized().selectionWithdrawn)
    }

    // A store whose only list was refused holds no location, and an import
    // into it takes the empty store's way in: the refused list survives it,
    // with everything else the store keeps beside its locations.
    @Test
    fun aRefusedListSurvivesTheImportOfAnotherListIntoAnOtherwiseEmptyStore() = runTest {
        val source = FakeLocationsDataSource()
        val repo = repoRespondingWith(source, MockEngine { request ->
            if (request.url.host == "partner.example.test") refusal() else respond(listBody)
        })
        assertNotNull(repo.importReport(theirsUrl).refused)
        repo.acceptVpnDisclosure(atMillis = 42L)
        repo.saveRoutingSettings(RoutingSettings(verboseDebugLogs = true))
        assertEquals(emptyList(), source.stored!!.locations)

        assertTrue(repo.importText("https://example.test/alpha"))

        val stored = source.stored!!
        assertEquals(listOf("https://example.test/alpha"), stored.locations.map { it.subscriptionUrl })
        assertEquals(listOf(theirsUrl), stored.refusedLists.map { it.subscriptionUrl })
        assertEquals(42L, stored.vpnDisclosureAcceptedAt)
        assertTrue(stored.routing.verboseDebugLogs)
        assertNotNull(stored.activeLocationId, "the imported list's server is selected, as into any empty store")
    }

    @Test
    fun aRefusedListIsAskedAgainWhenDueAndServedOnceTheHostLetsTheDeviceIn() = runTest {
        var now = 1_000L
        var refuses = true
        val url = "https://example.test/alpha"
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(MockEngine { if (refuses) refusal() else respond(listBody) }),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test"),
            nowEpochMs = { now }
        )
        assertNotNull(repo.importReport(url).refused)
        // The refusal was the list's last answer, and counts as its last refresh.
        assertEquals(SubscriptionRefreshReport.EMPTY, repo.refreshDueSubscriptions())

        refuses = false
        now += 12 * 3_600_000L
        val report = repo.refreshDueSubscriptions()

        assertEquals(1, report.updatedCount)
        assertFalse(report.hasFailures)
        assertEquals(listOf(url), source.stored!!.locations.map { it.subscriptionUrl })
        assertEquals(emptyList(), source.stored!!.refusedLists)
        // Served, its servers carry what the list says about itself; the
        // refusal's reason is not that any more.
        assertNull(source.stored!!.locations.single().metadata?.subscription?.announce)
    }

    // The request asked again without the id names no device, and a provider
    // that lets in only an app naming one says so to it. That says nothing of
    // this device, and its line is no server either: what the list held stays.
    @Test
    fun aRefusalOfTheRequestWithoutTheIdIsNoRefusalOfTheDevice() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val hwids = mutableListOf<String?>()
        val report = repoRespondingWith(source, MockEngine { request ->
            hwids += request.headers["x-hwid"]
            if (request.headers["x-hwid"] != null) throw IllegalStateException("no route to host")
            refusal(flag = "x-hwid-not-supported")
        }).refreshSubscription(url)

        assertEquals(listOf("hwid-test", null), hwids)
        assertEquals(SubscriptionRefreshError.Unreachable, report.failures.single().error)
        assertEquals(listOf("alpha"), source.stored!!.locations.map { it.storageId })
        assertEquals(emptyList(), source.stored!!.refusedLists)
    }

    // Everything else that is not a list is asked again as a browser would ask,
    // as it always was: a refusal is the one answer that ends the attempt.
    @Test
    fun everyOtherFailureIsStillAskedAgainWithoutTheId() = runTest {
        for (what in listOf("a 404", "a 500", "no answer", "an empty body", "a page")) {
            val url = "https://example.test/alpha"
            val hwids = mutableListOf<String?>()
            repoRespondingWith(subscribedSource(url), MockEngine { request ->
                hwids += request.headers["x-hwid"]
                when (what) {
                    "a 404" -> respond("gone", HttpStatusCode.NotFound)
                    "a 500" -> respond("boom", HttpStatusCode.InternalServerError)
                    "no answer" -> throw IllegalStateException("no route to host")
                    "an empty body" -> respond("   ")
                    else -> respond("<html>blocked</html>")
                }
            }).refreshSubscription(url)
            assertEquals(listOf("hwid-test", null), hwids, "after $what")
        }
    }

    // A list kept with no servers is as secret as one with them: it is shown by
    // name, never by the address its link was hiding, and shared as that link.
    @Test
    fun aRefusedEncryptedListStaysEncrypted() = runTest {
        val source = FakeLocationsDataSource()
        val link = "olcrtc://crypt1/$cryptUrlBlob"

        LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(MockEngine { refusal() }),
            cryptCodec = CryptCodec(CryptCodec.decodeMaster(cryptKeyB64)!!)
        ).importReport(link)

        val refused = source.stored!!.refusedLists.single()
        assertEquals(cryptUrl, refused.subscriptionUrl)
        assertEquals(link, refused.subscriptionOriginLink)
    }

    @Test
    fun deleteSubscriptionRemovesAListItsHostRefused() = runTest {
        val url = "https://example.test/partner"
        val source = FakeLocationsDataSource()
        val repo = repoRespondingWith(source, MockEngine { refusal() })
        assertNotNull(repo.importReport(url).refused)

        assertEquals(0, repo.deleteSubscription(url), "it had no locations to count")
        assertEquals(emptyList(), source.stored!!.refusedLists)
    }

    // ---- deleteSubscription ------------------------------------------------
    // Removing a subscription must take exactly its own locations with it, leave
    // manually added ones alone, and never leave the bundle pointing at a location
    // that no longer exists.

    private fun subEntry(storageId: String, subscriptionUrl: String?) = LocationEntry.from(
        storageId = storageId,
        location = LocationConfig(
            name = storageId,
            id = "room-$storageId",
            key = "k".repeat(64)
        ),
        subscriptionUrl = subscriptionUrl
    )

    private fun subSource(active: String?, vararg entries: LocationEntry) = FakeLocationsDataSource(
        LocationBundleV4(activeLocationId = active, locations = entries.toList())
    )

    // A session that moved by itself writes two things, the active location
    // and the line to start from next time, and writes them only if the choice
    // it is replacing is still the user's.

    @Test
    fun aMoveMakesTheNewLineActiveAndRemembersItAsOneChange() = runTest {
        val sub = "https://proofkit.org/sub/aaa"
        val source = subSource("a1", subEntry("a1", sub), subEntry("a2", sub))
        val repo = LocationsRepositoryImpl(source)
        val before = repo.changes.value

        assertTrue(repo.moveActiveLocation(fromStorageId = "a1", toStorageId = "a2", group = "exit"))

        assertEquals("a2", repo.getActiveLocationId())
        assertEquals("a2", repo.getSubscriptionSettings().lastKnownGoodTransport["exit"])
        assertEquals(before + 1, repo.changes.value)
    }

    @Test
    fun aMoveKeepsTheOtherSettingsAsTheyAreNow() = runTest {
        val sub = "https://proofkit.org/sub/aaa"
        val repo = LocationsRepositoryImpl(subSource("a1", subEntry("a1", sub), subEntry("a2", sub)))
        val settings = repo.getSubscriptionSettings()
        repo.saveSubscriptionSettings(
            settings.copy(smartConnect = !settings.smartConnect, lastKnownGoodTransport = mapOf("other" to "a1"))
        )

        assertTrue(repo.moveActiveLocation("a1", "a2", "exit"))

        val after = repo.getSubscriptionSettings()
        assertEquals(!settings.smartConnect, after.smartConnect)
        assertEquals(mapOf("other" to "a1", "exit" to "a2"), after.lastKnownGoodTransport)
    }

    @Test
    fun aMoveGivesWayToALocationTheUserChoseMeanwhile() = runTest {
        val sub = "https://proofkit.org/sub/aaa"
        val repo = LocationsRepositoryImpl(
            subSource("a1", subEntry("a1", sub), subEntry("a2", sub), subEntry("a3", sub))
        )
        repo.setActiveLocationId("a3")

        assertFalse(repo.moveActiveLocation("a1", "a2", "exit"))

        assertEquals("a3", repo.getActiveLocationId())
        assertNull(repo.getSubscriptionSettings().lastKnownGoodTransport["exit"])
    }

    @Test
    fun aMoveToALineThatHasLeftTheStoreStoresNothing() = runTest {
        val sub = "https://proofkit.org/sub/aaa"
        val repo = LocationsRepositoryImpl(subSource("a1", subEntry("a1", sub)))

        assertFalse(repo.moveActiveLocation("a1", "gone", "exit"))

        assertEquals("a1", repo.getActiveLocationId())
    }

    // A choice made on the board — a list folded away — is one field of the
    // settings changed, by a press. It is written as that: to the settings as
    // they are stored, not as a copy somebody read a while ago, and without
    // telling the app its locations changed.

    @Test
    fun aChangeToTheSettingsIsMadeToWhatIsStoredNow() = runTest {
        val sub = "https://proofkit.org/sub/aaa"
        val repo = LocationsRepositoryImpl(subSource("a1", subEntry("a1", sub), subEntry("a2", sub)))
        // A session moved by itself meanwhile and remembered the line it ended on.
        assertTrue(repo.moveActiveLocation("a1", "a2", "exit"))

        val stored = repo.changeSubscriptionSettings { it.withFolded(sub, folded = true) }

        assertTrue(stored.isFolded(sub))
        assertEquals(stored, repo.getSubscriptionSettings())
        assertEquals("a2", repo.getSubscriptionSettings().lastKnownGoodTransport["exit"])
    }

    @Test
    fun aChangeToTheSettingsIsNotAChangeOfLocations() = runTest {
        // Everything that listens for one reads the whole list again, and asks
        // every room how full it is: a hundred requests for a list folded.
        val sub = "https://proofkit.org/sub/aaa"
        val repo = LocationsRepositoryImpl(subSource("a1", subEntry("a1", sub)))
        val before = repo.changes.value

        repo.changeSubscriptionSettings { it.withFolded(sub, folded = true) }

        assertEquals(before, repo.changes.value)
        assertEquals("a1", repo.getActiveLocationId())
        assertEquals(1, repo.getAllLocations().size)
    }

    @Test
    fun deleteSubscriptionLeavesNothingAboutItInTheSettings() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val subB = "https://proofkit.org/sub/bbb"
        val repo = LocationsRepositoryImpl(subSource("a1", subEntry("a1", subA), subEntry("b1", subB)))
        repo.saveSubscriptionSettings(
            SubscriptionSettings(lowestSubscriptionUrls = setOf(subA, subB))
                .withFolded(subA, folded = true)
                .withFolded(subB, folded = true)
        )

        repo.deleteSubscription(subA)

        val settings = repo.getSubscriptionSettings()
        assertEquals(setOf(subB), settings.foldedSubscriptionUrls)
        assertEquals(setOf(subB), settings.lowestSubscriptionUrls)
    }

    @Test
    fun deleteSubscriptionRemovesOnlyThatSubscription() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val subB = "https://proofkit.org/sub/bbb"
        val source = subSource("a1", subEntry("a1", subA), subEntry("a2", subA), subEntry("b1", subB))
        val repo = LocationsRepositoryImpl(source)

        assertEquals(2, repo.deleteSubscription(subA))
        assertEquals(listOf("b1"), repo.getAllLocations().map { it.storageId })
    }

    @Test
    fun deleteSubscriptionKeepsManuallyAddedLocations() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val source = subSource("manual", subEntry("manual", null), subEntry("a1", subA))
        val repo = LocationsRepositoryImpl(source)

        assertEquals(1, repo.deleteSubscription(subA))
        assertEquals(listOf("manual"), repo.getAllLocations().map { it.storageId })
    }

    @Test
    fun deleteSubscriptionRepointsActiveLocation() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val subB = "https://proofkit.org/sub/bbb"
        val source = subSource("a1", subEntry("a1", subA), subEntry("b1", subB))
        val repo = LocationsRepositoryImpl(source)

        repo.deleteSubscription(subA)

        assertEquals("b1", repo.getActiveLocationId())
    }

    @Test
    fun deleteSubscriptionClearsActiveWhenNothingRemains() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val repo = LocationsRepositoryImpl(subSource("a1", subEntry("a1", subA)))

        assertEquals(1, repo.deleteSubscription(subA))
        assertTrue(repo.getAllLocations().isEmpty())
        assertNull(repo.getActiveLocationId())
    }

    @Test
    fun deleteSubscriptionTrimsTheUrl() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val repo = LocationsRepositoryImpl(subSource("a1", subEntry("a1", subA)))

        assertEquals(1, repo.deleteSubscription("  $subA  "))
    }

    @Test
    fun deleteSubscriptionIgnoresUnknownAndBlankUrls() = runTest {
        val subA = "https://proofkit.org/sub/aaa"
        val repo = LocationsRepositoryImpl(subSource("a1", subEntry("a1", subA)))

        assertEquals(0, repo.deleteSubscription("https://example.com/other"))
        assertEquals(0, repo.deleteSubscription("   "))
        assertEquals(1, repo.getAllLocations().size)
    }

    // --- partner (Happ) links -------------------------------------------

    private class FakeResolver(private val result: PartnerLinkResult) : PartnerLinkResolver {
        var asked: String? = null
        override suspend fun resolve(link: String): PartnerLinkResult {
            asked = link
            return result
        }
    }

    private val happLink = "happ://crypt5/fzvdQQSl2kKPyNPhAeRV4WSh12xLFV8"

    @Test
    fun aPastedHappLinkIsResolvedThenImportedLikeAnyOtherLink() = runTest {
        val source = FakeLocationsDataSource()
        val resolved = "olcrtc://wbstream?seichannel@room-01#${"c".repeat(64)}${'$'}DE / partner"
        val resolver = FakeResolver(PartnerLinkResult.Resolved(resolved))

        val imported = LocationsRepositoryImpl(
            dataSource = source,
            partnerLinkResolver = resolver,
        ).importText(happLink)

        assertTrue(imported)
        assertEquals(happLink, resolver.asked)
        assertNotNull(source.stored)
        assertEquals(1, source.stored!!.locations.size)
    }

    @Test
    fun anUnresolvableHappLinkFailsWithoutTouchingStoredLocations() = runTest {
        val source = FakeLocationsDataSource()
        val resolver = FakeResolver(PartnerLinkResult.NotFound)

        val imported = LocationsRepositoryImpl(
            dataSource = source,
            partnerLinkResolver = resolver,
        ).importText(happLink)

        assertFalse(imported)
        assertNull(source.stored)
    }

    @Test
    fun aPlainInlineConfigNeverGoesNearTheResolver() = runTest {
        val source = FakeLocationsDataSource()
        val resolver = FakeResolver(PartnerLinkResult.NotFound)
        val input = "olcrtc://wbstream?seichannel@room-01#${"d".repeat(64)}${'$'}RU / plain"

        val imported = LocationsRepositoryImpl(
            dataSource = source,
            partnerLinkResolver = resolver,
        ).importText(input)

        assertTrue(imported)
        assertNull(resolver.asked)
    }

    // --- an encrypted subscription stays encrypted -------------------------
    //
    // The crypt link exists so the endpoint is unreadable; the app decrypted it,
    // stored the plaintext URL and threw the link away, so the Server lists row
    // printed exactly what the encryption was hiding.

    private val cryptKeyB64 = "KioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKio="
    private val cryptUrlBlob =
        "PUtUSvcFHP7RaUdnoe3kiTxMd5t8R2fhKbezwNn4b346MTOM9L_Efda6HedlY1EVxprVRQu-bTgBUwc38IT-23bvBIhBrlFmgOviGK__tX3d8jKEsvUkFKcdd3FnO39oQFSFVcZH6ATMTJuMH6O8Sw"
    private val cryptUrl = "https://proofkit.org/sub/TESTTOKEN/olcrtc?crypt=1"
    private val subscriptionBody = "olcrtc://telemost?vp8channel@12345#deadbeefdeadbeef\$DE"

    @Test
    fun aCryptLinkImportRemembersTheLinkItArrivedAs() = runTest {
        val source = FakeLocationsDataSource()
        val engine = MockEngine { respond(subscriptionBody) }
        val link = "olcrtc://crypt1/$cryptUrlBlob"

        val ok = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            cryptCodec = CryptCodec(CryptCodec.decodeMaster(cryptKeyB64)!!)
        ).importText(link)

        assertTrue(ok)
        val entry = source.stored!!.locations.single()
        assertEquals(cryptUrl, entry.subscriptionUrl)
        assertEquals(link, entry.subscriptionOriginLink)
    }

    @Test
    fun aHappLinkImportRemembersThePastedLinkNotTheResolvedOne() = runTest {
        // What the user can paste again is the happ link; the crypt1 link the
        // coordinator handed back is an implementation detail of the resolver.
        val source = FakeLocationsDataSource()
        val engine = MockEngine { respond(subscriptionBody) }
        val resolver = FakeResolver(PartnerLinkResult.Resolved("olcrtc://crypt1/$cryptUrlBlob"))

        val ok = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            cryptCodec = CryptCodec(CryptCodec.decodeMaster(cryptKeyB64)!!),
            partnerLinkResolver = resolver
        ).importText(happLink)

        assertTrue(ok)
        assertEquals(happLink, source.stored!!.locations.single().subscriptionOriginLink)
    }

    @Test
    fun aPlainSubscriptionHasNoOriginLink() = runTest {
        val source = FakeLocationsDataSource()
        val engine = MockEngine { respond(subscriptionBody) }

        val ok = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine)
        ).importText("https://example.test/sub/plain")

        assertTrue(ok)
        assertNull(source.stored!!.locations.single().subscriptionOriginLink)
    }

    @Test
    fun refreshLeavesTheChosenServerChosen() = runTest {
        // Reported from a phone: refreshing a server list jumped the connection to
        // whatever was first in it. Refresh must update the list and nothing else —
        // it is not a command to change servers, still less to re-dial one.
        val body = "olcrtc://telemost?vp8channel@11111#deadbeefdeadbeef\$DE\n" +
            "olcrtc://telemost?vp8channel@22222#deadbeefdeadbeef\$NL"
        val url = "https://example.test/sub/two"
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(MockEngine { respond(body) })
        )

        repo.importText(url)
        val entries = source.stored!!.locations
        assertEquals(2, entries.size, "the fixture must offer a choice to keep")
        val chosen = entries.last().storageId
        repo.setActiveLocationId(chosen)

        repo.refreshSubscription(url)

        assertEquals(
            chosen,
            source.stored!!.activeLocationId,
            "the selection survived the refresh and must not be reassigned"
        )
    }

    @Test
    fun refreshMovesTheSelectionOnlyWhenItsServerIsGone() = runTest {
        // The behaviour the broken condition was reaching for: a chosen server that
        // the provider dropped has to land somewhere, and the first entry is the
        // least surprising place.
        val url = "https://example.test/sub/two"
        val source = FakeLocationsDataSource()
        val bodies = listOf(
            "olcrtc://telemost?vp8channel@11111#deadbeefdeadbeef\$DE\n" +
                "olcrtc://telemost?vp8channel@22222#deadbeefdeadbeef\$NL",
            "olcrtc://telemost?vp8channel@11111#deadbeefdeadbeef\$DE"
        )
        var call = 0
        val repo = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(MockEngine { respond(bodies[minOf(call++, 1)]) })
        )

        repo.importText(url)
        val gone = source.stored!!.locations.last().storageId
        repo.setActiveLocationId(gone)

        repo.refreshSubscription(url)

        val remaining = source.stored!!.locations
        assertEquals(1, remaining.size)
        assertEquals(remaining.single().storageId, source.stored!!.activeLocationId)
    }

    @Test
    fun refreshKeepsTheEncryptedOrigin() = runTest {
        // A refresh re-imports from the stored plaintext URL and never sees the
        // encrypted link, so without an explicit carry-over the first auto-refresh
        // would quietly declassify the subscription.
        val link = "olcrtc://crypt1/$cryptUrlBlob"
        val stored = LocationBundleV4(
            activeLocationId = "alpha",
            locations = listOf(
                LocationEntry.from(
                    "alpha",
                    LocationConfig("Alpha", "12345", "deadbeefdeadbeef", LocationConfig.PROVIDER_TELEMOST),
                    subscriptionUrl = cryptUrl,
                    subscriptionOriginLink = link
                )
            )
        )
        val source = FakeLocationsDataSource(stored = stored)
        val engine = MockEngine { respond(subscriptionBody) }

        LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine)
        ).refreshSubscription(cryptUrl)

        assertEquals(link, source.stored!!.locations.single().subscriptionOriginLink)
    }

    @Test
    fun sharingAnEncryptedSubscriptionHandsBackTheEncryptedLink() {
        val encrypted = LocationEntry.from(
            "alpha",
            LocationConfig("Alpha", "room-a", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://proofkit.org/sub/TESTTOKEN/olcrtc?crypt=1",
            subscriptionOriginLink = "olcrtc://crypt1/blob"
        )
        val plain = LocationEntry.from(
            "beta",
            LocationConfig("Beta", "room-b", "b".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/sub"
        )

        val items = ConfigShareService.subscriptionShareItems(listOf(encrypted, plain))

        assertEquals("olcrtc://crypt1/blob", items.single { it.originLink != null }.shareText)
        assertEquals("https://example.test/sub", items.single { it.originLink == null }.shareText)
    }

    @Test
    fun theOriginLinkSurvivesASaveRoundTrip() {
        // normalized() rebuilds the entry field by field and runs on every save, so
        // a field it forgets is a field that never persists.
        val entry = LocationEntry.from(
            "alpha",
            LocationConfig("Alpha", "12345", "deadbeefdeadbeef", LocationConfig.PROVIDER_TELEMOST),
            subscriptionUrl = "https://example.test/sub",
            subscriptionOriginLink = "olcrtc://crypt1/blob"
        )
        assertEquals("olcrtc://crypt1/blob", entry.normalized().subscriptionOriginLink)
    }

    @Test
    fun importsTheRoomsHeaderAndKeepsItInTheStoredEntry() = runTest {
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(source)
        val key = "a".repeat(64)
        val input = """
            olcrtc://telemost?vp8channel@11115586048655#$key${'$'}Israel
            ##name: IL-1
            ##rooms: 81055221156696, 52664279650262 11115586048655
        """.trimIndent()

        assertTrue(repo.importText(input))

        val entry = repo.getAllLocations().single()
        assertEquals(listOf("81055221156696", "52664279650262"), entry.location.failoverRoomIds)
        assertEquals(
            listOf("11115586048655", "81055221156696", "52664279650262"),
            entry.location.failoverRooms()
        )
        // The stored shape carries them too. A list that lived only in the
        // parse was dropped on save, and the one room the client then knew was
        // the one the server was about to retire.
        assertEquals(
            listOf("81055221156696", "52664279650262"),
            source.stored?.locations?.single()?.failoverRooms
        )
    }

    @Test
    fun refreshReplacesTheFailoverRoomsWithWhatTheServerAdvertisesNow() = runTest {
        val key = "c".repeat(64)
        var body = "olcrtc://telemost?vp8channel@R1#$key${'$'}Room\n##rooms: R2"
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText("https://example.test/sub"))
        assertEquals(listOf("R1", "R2"), repo.getActiveLocation()?.location?.failoverRooms())

        // The server retired R1 and now serves R2 with R3 on standby.
        body = "olcrtc://telemost?vp8channel@R2#$key${'$'}Room\n##rooms: R3"
        repo.refreshSubscriptions()

        assertEquals(listOf("R2", "R3"), repo.getActiveLocation()?.location?.failoverRooms())
    }

    // A partner's list names every carrier of one origin alike ("DE · VP8"). The
    // first refresh after WB and SaluteJazz joined the Telemost line kept Telemost
    // under its old id and named the WB line after it, "imported_de_vp8" again:
    // normalized() keeps one entry per id, so WB vanished and SaluteJazz stayed.
    @Test
    fun refreshKeepsEveryCarrierOfAnOriginEvenWhenTheirNamesMatch() = runTest {
        val key = "c".repeat(64)
        val telemost = "olcrtc://telemost?vp8channel@T1#$key${'$'}DE · VP8"
        var body = telemost
        val source = FakeLocationsDataSource()
        val repo = LocationsRepositoryImpl(source, HttpClient(MockEngine { respond(body) }))
        assertTrue(repo.importText("https://example.test/sub"))
        assertEquals(1, source.stored?.locations?.size)

        body = listOf(
            telemost,
            "olcrtc://wbstream?vp8channel@W1#$key${'$'}DE · VP8",
            "olcrtc://salutejazz?datachannel@code:pass#$key${'$'}DE · VP8",
        ).joinToString("\n")
        repo.refreshSubscriptions()
        repo.refreshSubscriptions()

        val stored = source.stored?.locations.orEmpty()
        assertEquals(
            listOf("T1", "W1", "code:pass"),
            stored.map { it.location.id },
            "every carrier's line survives the refresh, each once"
        )
        assertEquals(stored.size, stored.map { it.storageId }.toSet().size, "no two lines share an id")
    }

    private class FakeLocationsDataSource(
        var stored: LocationBundleV4? = null,
        private val legacy: List<Pair<String, String>> = emptyList(),
        private val legacyActive: String? = null
    ) : LocationsDataSource {

        override suspend fun loadLocationBundle(): LocationBundleV4? = stored

        override suspend fun saveLocationBundle(bundle: LocationBundleV4) {
            stored = bundle
        }

        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = legacy

        override suspend fun loadLegacyActiveLocationId(): String? = legacyActive
    }

    private class StaticIdentityProvider(
        private val value: String
    ) : DeviceIdentityProvider {
        override suspend fun hwid(): String = value
    }
}
