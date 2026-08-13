package com.discord.activities

import com.discord.api.channel.Channel
import com.discord.api.embeddedactivities.EmbeddedActivityConfig
import com.discord.api.embeddedactivities.EmbeddedActivityDiscordConfig
import com.discord.api.embeddedactivities.EmbeddedActivityInstance
import com.discord.api.embeddedactivities.EmbeddedActivityLaunchBody
import com.discord.api.embeddedactivities.EmbeddedActivityLocation
import com.discord.api.embeddedactivities.EmbeddedActivityParticipant
import com.discord.api.embeddedactivities.EmbeddedActivityPlatformConfig
import com.discord.api.embeddedactivities.EmbeddedActivityProxyTicketRequest
import com.discord.api.embeddedactivities.EmbeddedActivityShelf
import com.discord.api.embeddedactivities.EmbeddedActivityShelfApplication
import com.discord.gateway.io.IncomingParser
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.restapi.EmbeddedActivitiesAPI
import com.discord.restapi.RestAPIBuilder
import com.discord.stores.StoreEmbeddedActivities
import com.discord.stores.updates.ObservationDeck
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedActivitiesContractsTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun gatewayV2AndShelfModelsAreTyped() {
        val event = IncomingParser(gson).parse(
            """{"op":0,"t":"EMBEDDED_ACTIVITY_UPDATE_V2","d":{"application_id":"12","composite_instance_id":"i-34-gc-1-2","instance_id":"34","launch_id":"35","location":{"id":"gc-1-2","kind":"gc","channel_id":"2","guild_id":"1"},"participants":[{"user_id":"7","session_id":"session","nonce":null}]}}""",
        )
        val instance = event.data as EmbeddedActivityInstance
        assertEquals(12L, instance.applicationId)
        assertEquals(2L, instance.location.channelId)
        assertEquals(listOf(7L), instance.participants.map { it.userId })

        val shelf = gson.fromJson(SHELF_JSON, EmbeddedActivityShelf::class.java)
        assertEquals(listOf("android", "web"), shelf.activities.single().supportedPlatforms)
        assertEquals("Watch Together", shelf.applications.single().name)
        assertEquals(3, shelf.activities.single().shelfRank)
    }

    @Test
    fun restRoutesAndSensitiveBodiesMatchContract() {
        val server = MockWebServer()
        server.start()
        try {
            val api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
                .build(EmbeddedActivitiesAPI::class.java)
            server.enqueue(MockResponse().setResponseCode(204))
            api.launch(2, 12, EmbeddedActivityLaunchBody("session", 1)).toBlocking().single()
            server.takeRequest().also {
                assertEquals("/api/v9/activities/2/12", it.path)
                assertEquals("POST", it.method)
                assertEquals("{\"session_id\":\"session\",\"guild_id\":1}", it.body.readUtf8())
            }

            server.enqueue(MockResponse().setBody("{\"ticket\":\"secret\"}"))
            val ticket = api.proxyTicket(12, EmbeddedActivityProxyTicketRequest(2)).toBlocking().single()
            assertEquals("/api/v9/applications/12/proxy-tickets", server.takeRequest().path)
            assertFalse(ticket.toString().contains("secret"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun textUsesPrimaryEntryPointWhileVoiceUsesDirectLaunch() {
        val server = MockWebServer()
        server.start()
        try {
            val api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
                .build(EmbeddedActivitiesAPI::class.java)
            server.enqueue(
                MockResponse().setBody(
                    """{"application_commands":[{"id":"99","type":4,"application_id":"12","name":"launch","version":"1"}]}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(204))
            EmbeddedActivitySessionManager.start(api, 12, channel(type = 0), "session").toBlocking().single()
            assertEquals("/api/v9/applications/12/application-command-index", server.takeRequest().path)
            server.takeRequest().also {
                assertEquals("/api/v9/interactions", it.path)
                assertTrue(it.body.readUtf8().contains("\"source\":\"TEXT\""))
            }

            server.enqueue(MockResponse().setResponseCode(204))
            EmbeddedActivitySessionManager.start(api, 12, channel(type = 2), "session").toBlocking().single()
            assertEquals("/api/v9/activities/2/12", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discordLaunchHandlerIsAnActivityEntryPointEvenWhenTypedAsChatInput() {
        val command = RemoteApplicationCommand(
            id = "99",
            applicationId = 12,
            name = "wordle",
            options = emptyList(),
            guildId = 1,
            version = "1",
            type = 1,
            handler = EmbeddedActivityLauncher.DISCORD_LAUNCH_ACTIVITY_HANDLER,
        )

        assertTrue(EmbeddedActivityLauncher.isPrimaryEntryPoint(command))
    }

    @Test
    fun storeCachesShelfAndReconcilesV2Participants() {
        var now = 100L
        val store = StoreEmbeddedActivities(ObservationDeck(), now = { now })
        val shelf = gson.fromJson(SHELF_JSON, EmbeddedActivityShelf::class.java)
        store.handleShelfFetchSuccess(1, shelf)
        store.snapshotData()
        assertFalse(store.shouldFetchShelf(1))
        now += 6 * 60 * 60 * 1000L + 1
        assertTrue(store.shouldFetchShelf(1))

        val instance = instance()
        store.handleEmbeddedActivityUpdateV2(instance)
        store.snapshotData()
        assertEquals(instance, store.getInstancesForChannel(2)[12])
        store.handleEmbeddedActivityUpdateV2(instance.copy(participants = emptyList()))
        store.snapshotData()
        assertTrue(store.getInstancesForChannel(2).isEmpty())
    }

    @Test
    fun urlContainsExactMobileContextAndRedactsSession() {
        assertEquals(
            EmbeddedActivityDiscordConfig("//discord.com/api", "production", "cdn.discordapp.com"),
            EmbeddedActivitySessionManager.rpcConfig,
        )
        val request = EmbeddedActivityLaunchRequest(12, 2, customId = "custom value", referrerId = "ref")
        val url = EmbeddedActivitySessionManager.buildUrl(instance(), channel(), "proxy secret", request, "frame")
        assertEquals(
            "https://12.discordsays.com/?instance_id=i-34-gc-1-2&location_id=gc-1-2&launch_id=35&discord_proxy_ticket=proxy%20secret&channel_id=2&guild_id=1&custom_id=custom%20value&referrer_id=ref&frame_id=frame&platform=mobile&mobile_app_version=340.12",
            url,
        )
        val session = EmbeddedActivitySession(
            EmbeddedActivityShelfApplication(12, "Activity"),
            config(),
            instance(),
            channel(),
            EmbeddedActivityDiscordConfig("//discord.com/api", "production", "cdn.discordapp.com"),
            "proxy secret",
            url,
            "frame",
        )
        assertFalse(session.toString().contains("proxy secret"))
        assertFalse(session.toString().contains("discordsays"))
    }

    @Test
    fun bridgeRejectsWrongNonceOversizedAndDestroyedMessages() {
        assertTrue(EmbeddedActivityWebView.acceptsBridgeMessage("nonce", "nonce", "[]", false))
        assertFalse(EmbeddedActivityWebView.acceptsBridgeMessage("wrong", "nonce", "[]", false))
        assertFalse(
            EmbeddedActivityWebView.acceptsBridgeMessage(
                "nonce",
                "nonce",
                "x".repeat(EmbeddedActivityWebView.MAX_BRIDGE_MESSAGE_LENGTH + 1),
                false,
            ),
        )
        assertFalse(EmbeddedActivityWebView.acceptsBridgeMessage("nonce", "nonce", "[]", true))
    }

    private fun config() = EmbeddedActivityConfig(
        applicationId = 12,
        activityPreviewVideoAssetId = null,
        supportedPlatforms = listOf("android", "web"),
        defaultOrientationLockState = 2,
        tabletDefaultOrientationLockState = 1,
        requiresAgeGate = false,
        legacyResponsiveAspectRatio = false,
        premiumTierRequirement = null,
        freePeriodStartsAt = null,
        freePeriodEndsAt = null,
        clientPlatformConfig = mapOf("android" to EmbeddedActivityPlatformConfig(0, releasePhase = "global_launch")),
        shelfRank = 3,
        hasCspException = false,
        displaysAdvertisements = false,
    )

    private fun instance() = EmbeddedActivityInstance(
        12,
        "i-34-gc-1-2",
        34,
        35,
        EmbeddedActivityLocation("gc-1-2", "gc", 2, 1),
        listOf(EmbeddedActivityParticipant(7, sessionId = "session")),
    )

    private fun channel(type: Int = 2): Channel = gson.fromJson(
        """{"id":2,"guild_id":1,"type":$type,"name":"Channel"}""",
        Channel::class.java,
    )

    private companion object {
        const val SHELF_JSON = """
            {"activities":[{"application_id":"12","activity_preview_video_asset_id":null,"supported_platforms":["android","web"],"default_orientation_lock_state":2,"tablet_default_orientation_lock_state":1,"requires_age_gate":false,"legacy_responsive_aspect_ratio":false,"premium_tier_requirement":null,"free_period_starts_at":null,"free_period_ends_at":null,"client_platform_config":{"android":{"label_type":0,"label_until":null,"release_phase":"global_launch","omit_badge_from_surfaces":[]}},"shelf_rank":3,"has_csp_exception":false,"displays_advertisements":false}],"applications":[{"id":"12","name":"Watch Together","embedded_activity_config":{"supported_platforms":["android","web"],"default_orientation_lock_state":2,"tablet_default_orientation_lock_state":1,"requires_age_gate":false,"legacy_responsive_aspect_ratio":false,"client_platform_config":{},"shelf_rank":3,"has_csp_exception":false,"displays_advertisements":false}}],"assets":{}}
        """
    }
}
