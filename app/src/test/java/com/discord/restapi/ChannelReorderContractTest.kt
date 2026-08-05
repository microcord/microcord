package com.discord.restapi

import com.discord.api.channel.Channel
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.servers.GuildChannelsSort
import com.discord.widgets.servers.SettingsChannelListAdapter
import com.discord.widgets.servers.submitChannelReorder
import com.google.gson.JsonParser
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class ChannelReorderContractTest {
    private val server = MockWebServer()
    private val diagnostics = mutableListOf<ChannelReorderDiagnostics.Event>()
    private val clients = linkedMapOf<String, OkHttpClient>()

    init {
        ChannelReorderDiagnostics.listener = diagnostics::add
    }

    @After
    fun stopServer() {
        ChannelReorderDiagnostics.listener = null
        RestAPIBuilder.clientCallback = { _, _ -> }
        RestAPI.reset()
        server.shutdown()
    }

    @Test
    fun `production drag path sends reference positions headers and explicit parents`() {
        server.enqueue(MockResponse().setResponseCode(204))
        installProductionClients()
        val original = listOf(
            category(0, 0, manageable = false),
            category(CATEGORY_A, 40),
            channel(CHANNEL_A, 90, CATEGORY_A),
            category(CATEGORY_B, 100),
            channel(CHANNEL_B, 110, CATEGORY_B),
        )
        val reordered = listOf(original[0], original[2], original[1], original[4], original[3])
        val updates = GuildChannelsSort.changedPositions(original, reordered)
            .mapValues { (_, update) -> SettingsChannelListAdapter.UpdatedPosition(update.position, update.parentId) }

        submitChannelReorder(GUILD_ID, updates).toBlocking().subscribe()

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonArray
        assertEquals(
            listOf(
                "main",
                "serializeNulls",
                "applicationDirectory",
                "embeddedActivities",
                "embeddedActivityOAuth",
                "embeddedActivityOAuthToken",
            ),
            clients.keys.toList(),
        )
        assertNotSame(clients.getValue("main"), clients.getValue("serializeNulls"))
        assertEquals("PATCH", request.method)
        assertEquals("/api/v9/guilds/$GUILD_ID/channels", request.path)
        assertEquals("test-auth", request.getHeader("Authorization"))
        assertEquals("test-fingerprint", request.getHeader("X-Fingerprint"))
        assertEquals("en-US", request.getHeader("X-Discord-Locale"))
        assertEquals("en-US,en;q=0.9", request.getHeader("Accept-Language"))
        assertEquals(AnalyticSuperProperties.USER_AGENT, request.getHeader("User-Agent"))
        assertFalse(request.getHeader("X-Super-Properties").isNullOrBlank())
        assertEquals(listOf(CHANNEL_A, CATEGORY_A, CHANNEL_B, CATEGORY_B), body.map { it.asJsonObject["id"].asLong })
        assertTrue(body.all { it.asJsonObject["id"].asJsonPrimitive.isNumber })
        assertEquals(listOf(1, 2, 3, 4), body.map { it.asJsonObject["position"].asInt })
        assertTrue(body[0].asJsonObject["parent_id"].isJsonNull)
        assertFalse(body[1].asJsonObject.has("parent_id"))
        assertEquals(CATEGORY_A, body[2].asJsonObject["parent_id"].asLong)
        assertTrue(body[2].asJsonObject["parent_id"].asJsonPrimitive.isNumber)
        assertFalse(body[3].asJsonObject.has("parent_id"))
        assertEquals(listOf("request", "response"), diagnostics.map { it.phase })
        assertEquals("array<object{id:number, parent_id:null, position:number}|object{id:number, parent_id:number, position:number}|object{id:number, position:number}>", diagnostics[0].requestBodyShape)
        assertEquals("empty", diagnostics[1].responseBodyShape)
    }

    @Test
    fun `production response adapter emits null for 200 body and 204`() {
        installProductionClients()
        val values = mutableListOf<Void?>()
        var completions = 0
        var failures = 0

        listOf(
            MockResponse().setBody("""[{"id":20,"position":3}]"""),
            MockResponse().setResponseCode(204),
        ).forEach { response ->
            server.enqueue(response)
            submitChannelReorder(GUILD_ID, mapOf(20L to SettingsChannelListAdapter.UpdatedPosition(3, null)))
                .subscribe(values::add, { failures++ }, { completions++ })
        }

        assertEquals(listOf<Void?>(null, null), values)
        assertEquals(2, completions)
        assertEquals(0, failures)
        assertEquals(listOf("request", "response", "request", "response"), diagnostics.map { it.phase })
        assertEquals(listOf(200, 204), diagnostics.filter { it.phase == "response" }.map { it.status })
    }

    @Test
    fun `production path reports one sanitized terminal error`() {
        installProductionClients()
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"message\":\"invalid position\"}"))
        var failure: Throwable? = null

        submitChannelReorder(GUILD_ID, mapOf(20L to SettingsChannelListAdapter.UpdatedPosition(3, null)))
            .subscribe({}, { failure = it })

        assertTrue(failure is HttpException)
        assertEquals(listOf("request", "error"), diagnostics.map { it.phase })
        assertEquals("http", diagnostics.last().failureKind)
        assertEquals("HttpException", diagnostics.last().failureType)
        assertFalse(diagnostics.joinToString().contains("test-auth"))
        assertFalse(diagnostics.joinToString().contains("invalid position"))
    }

    private fun installProductionClients() {
        RestAPIBuilder.clientCallback = { name, client -> clients[name] = client }
        RestAPI.install(
            RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES),
            listOf(
                RequiredHeadersInterceptor(headers),
            ),
        )
    }

    private fun category(id: Long, position: Int, manageable: Boolean = true) =
        SettingsChannelListAdapter.CategoryItem(null, id, position, true, manageable, true)

    private fun channel(id: Long, position: Int, parentId: Long) = SettingsChannelListAdapter.ChannelItem(
        Channel(
            topic = null,
            type = Channel.GUILD_TEXT,
            guildId = GUILD_ID,
            name = "channel",
            lastMessageId = 0,
            id = id,
            ownerId = 0,
            recipientIds = null,
            recipients = null,
            position = position,
            permissionOverwrites = null,
            userLimit = 0,
            bitrate = 0,
            icon = null,
            originChannelId = 0,
            applicationId = 0,
            nicks = null,
            nsfw = false,
            parentId = parentId,
            memberListId = null,
            rateLimitPerUser = 0,
            defaultAutoArchiveDuration = null,
            rtcRegion = null,
            flags = null,
            guildHashes = null,
            availableTags = null,
            appliedTags = null,
            threadMetadata = null,
            messageCount = null,
            memberCount = null,
            memberIdsPreview = null,
            member = null,
        ),
        isDraggable = true,
        parentId = parentId,
        canManageCategoryOfChannel = true,
    )

    private companion object {
        const val GUILD_ID = 10L
        const val CATEGORY_A = 9_223_372_036_854_775_000L
        const val CATEGORY_B = 9_223_372_036_854_775_001L
        const val CHANNEL_A = 9_223_372_036_854_775_002L
        const val CHANNEL_B = 9_223_372_036_854_775_003L

        val headers = object : RequiredHeadersInterceptor.HeadersProvider {
            override fun getAuthToken() = "test-auth"
            override fun getFingerprint() = "test-fingerprint"
            override fun getLocale() = "en-US"
            override fun getAcceptLanguages() = "en-US,en;q=0.9"
            override fun getSpotifyToken(): String? = null
        }
    }
}
