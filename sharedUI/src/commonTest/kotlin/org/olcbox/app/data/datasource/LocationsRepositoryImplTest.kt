package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.crypt.CryptCodec
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.share.ConfigShareService
import io.ktor.http.HttpStatusCode
import org.olcbox.app.net.PartnerLinkResolver
import org.olcbox.app.net.PartnerLinkResult
import org.olcbox.app.data.repository.SubscriptionRefreshError
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
            LocationConfig("Amsterdam", "room-a", "key-a", LocationConfig.PROVIDER_JAZZ)
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
            listOf(LocationConfig.PROVIDER_WB_STREAM, LocationConfig.PROVIDER_JAZZ),
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
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_JAZZ)
        )
        assertEquals(
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_JAZZ),
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

    @Test
    fun subscriptionImportFallsBackWhenIdentityResponseIsNotConfig() = runTest {
        val userAgents = mutableListOf<String?>()
        val hwids = mutableListOf<String?>()
        val engine = MockEngine { request ->
            userAgents += request.headers[HttpHeaders.UserAgent]
            hwids += request.headers["x-hwid"]
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
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).importText("http://example.test/sub.txt")

        val bundle = source.stored
        assertTrue(imported)
        assertNotNull(bundle)
        assertEquals(2, userAgents.size)
        assertEquals(CurrentAppInfo.userAgent, userAgents[0])
        assertEquals("hwid-test", hwids[0])
        assertTrue(userAgents[1]?.startsWith("Mozilla/5.0") == true)
        assertNull(hwids[1])
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
    fun refreshReportsUnsupportedTransportRowsInsteadOfSilentlyDroppingThem() = runTest {
        val url = "https://example.test/alpha"
        val source = subscribedSource(url)
        val uuid = "55555555-5555-5555-5555-555555555555"
        val engine = MockEngine {
            respond(
                "vless://$uuid@tcp.example:443?type=tcp&sni=tcp.example#TCP\n" +
                    "vless://$uuid@ws.example:443?type=ws&sni=ws.example#WS"
            )
        }

        val report = repoRespondingWith(source, engine).refreshSubscription(url)

        assertEquals(1, report.updatedCount)
        assertEquals(1, report.skippedUnsupportedCount)
        assertTrue(report.singleMessage().contains("1 unsupported profiles skipped"))
        assertEquals(1, source.stored!!.locations.size)
    }

    @Test
    fun blankUrlRefreshIsANoOpReport() = runTest {
        val source = subscribedSource("https://example.test/alpha")
        val engine = MockEngine { respond("unused") }

        val report = repoRespondingWith(source, engine).refreshSubscription("   ")

        assertEquals(0, report.updatedCount)
        assertFalse(report.hasFailures)
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
