package com.discord.gateway

import android.content.SharedPreferences
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.guildmember.GuildMember
import com.discord.api.guildmember.GuildMemberRemove
import com.discord.api.guildmember.GuildMembersChunk
import com.discord.api.role.GuildRoleCreate
import com.discord.api.role.GuildRoleDelete
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.gateway.io.Identify
import com.discord.gateway.io.IdentifyClientState
import com.discord.gateway.io.IncomingParser
import com.discord.gateway.io.Outgoing
import com.discord.gateway.rest.RestApi
import com.discord.gateway.rest.RestClient
import com.discord.gateway.rest.RestConfig
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelGateway
import com.discord.restapi.RequiredHeadersInterceptor
import com.discord.restapi.RestAPIBuilder
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.lang.reflect.Proxy
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayContractTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .serializeNulls()
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun discoveryPersistsAcrossRestartAndRejectsInvalidUrls() {
        val preferences = memoryPreferences()
        var calls = 0
        assertEquals("wss://gateway.example", GatewayDiscovery(preferences) { calls++; ModelGateway("wss://gateway.example") }.discover())
        assertEquals("wss://gateway.example", GatewayDiscovery(preferences) { calls++; ModelGateway(null) }.discover())
        assertEquals(1, calls)
        assertEquals("wss://gateway.example", preferences.getString(GatewayDiscovery.GATEWAY_URL_CACHE_KEY, null))

        preferences.edit().putString(GatewayDiscovery.GATEWAY_URL_CACHE_KEY, "ws://cached.example").apply()
        assertEquals("wss://fresh.example", GatewayDiscovery(preferences) { ModelGateway("wss://fresh.example") }.discover())
        assertThrows(IllegalArgumentException::class.java) {
            GatewayDiscovery(preferences) { ModelGateway(null) }.discover(ignoreSticky = true)
        }
        assertEquals(null, preferences.getString(GatewayDiscovery.GATEWAY_URL_CACHE_KEY, null))

        listOf(null, "not a url", "ws://gateway.example", "https://gateway.example").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { GatewayDiscovery { ModelGateway(url) }.discover() }
        }
    }

    @Test
    fun controlPayloadsUseExactWireNamesAndExplicitNullData() {
        assertEquals("{\"op\":7,\"d\":null}", gson.toJson(Outgoing(7)))
        val identify = gson.toJsonTree(
            Identify("token", 100, true, 351, emptyMap(), IdentifyClientState(emptyMap(), 0, 1, 2)),
        ).asJsonObject
        assertTrue(identify.has("client_state"))
        assertTrue(identify["client_state"].asJsonObject.has("useruser_guild_settings_version"))
        assertFalse(identify["client_state"].asJsonObject.has("user_guild_settings_version"))
        val nullableState = gson.toJsonTree(Identify("token", 100, true, 351, emptyMap(), null)).asJsonObject
        assertTrue(nullableState["client_state"].isJsonNull)
    }

    @Test
    fun exactGatewayJvmClassesAreConsolidatedAndLogsAreRedacted() {
        val nestedPayloads = listOf(
            "ApplicationCommandRequest", "CallConnect", "CreateStream", "DeleteStream",
            "ForumUnreadsRequest", "ForumUnreadsRequestThread", "GuildMembersRequest",
            "GuildSubscriptions", "GuildSubscriptionsUpdate", "Identify", "IdentifyClientState",
            "PresenceUpdate", "Resume", "StreamPing", "VoiceStateUpdate",
            "VoiceStateUpdateNoPreferredRegion", "WatchStream",
        )
        listOf(
            "com.discord.gateway.GatewayEventHandler",
            "com.discord.gateway.GatewaySocketLogger",
            "com.discord.gateway.io.Incoming",
            "com.discord.gateway.io.Outgoing",
            "com.discord.gateway.io.OutgoingPayload",
            "com.discord.gateway.rest.RestApi",
            "com.discord.gateway.rest.RestClient",
            "com.discord.gateway.rest.RestConfig",
        ).forEach { assertEquals(it, Class.forName(it).name) }
        nestedPayloads.forEach {
            assertEquals("com.discord.gateway.io.OutgoingPayload\$$it", Class.forName("com.discord.gateway.io.OutgoingPayload\$$it").name)
        }
        assertThrows(ClassNotFoundException::class.java) { Class.forName("com.discord.gateway.io.Identify") }

        val redacted = GatewayLogRedactor.redact(
            """{"op":2,"d":{"token":"secret","auth_session_id_hash":"hash","static_client_session_id":"session","nested":{"content":"private"},"safe":7}}""",
        )
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("\"hash\""))
        assertFalse(redacted.contains("\"session\""))
        assertFalse(redacted.contains("private"))
        assertTrue(redacted.contains("<redacted>"))
        assertTrue(redacted.contains("\"safe\":7"))
    }

    @Test
    fun gatewayRestContractUsesVersionedDiscoveryAndRejectsMissingUrl() {
        val provider = object : RequiredHeadersInterceptor.HeadersProvider {
            override fun getAuthToken(): String? = null
            override fun getFingerprint(): String? = null
            override fun getLocale(): String? = null
            override fun getAcceptLanguages(): String? = null
            override fun getUserAgent(): String = "fixture"
            override fun getSpotifyToken(): String? = null
        }
        val server = MockWebServer()
        try {
            val config = RestConfig(server.url("/api/").toString(), provider)
            assertTrue(config.optionalInterceptors.isEmpty())
            RestClient::class.java.getDeclaredField("restApi").apply {
                isAccessible = true
                set(null, RestAPIBuilder(config.baseUrl, CookieJar.NO_COOKIES).build(RestApi::class.java))
            }
            server.enqueue(MockResponse().setBody("{}"))

            assertThrows(IllegalArgumentException::class.java) { RestClient.getGateway().toBlocking().single() }
            assertEquals("/api/v9/gateway", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun parserTypesReadyCoreEventsAndSkipsUnknownDispatches() {
        val parser = IncomingParser(gson)
        val ready = parser.parse(requireNotNull(javaClass.getResource("/fixtures/gateway/ready_core.json")).readText())
        val create = parser.parse(stageEvent("STAGE_INSTANCE_CREATE", "topic"))
        val update = parser.parse(stageEvent("STAGE_INSTANCE_UPDATE", "updated"))
        val delete = parser.parse(stageEvent("STAGE_INSTANCE_DELETE", "updated"))
        assertEquals("sanitized-session", (ready.data as ModelPayload).sessionId)
        assertEquals(StageInstancePrivacyLevel.GUILD_ONLY, (create.data as StageInstance).privacyLevel)
        assertEquals("updated", (update.data as StageInstance).topic)
        assertEquals(3L, (delete.data as StageInstance).channelId)

        val member = parser.parse("""{"op":0,"t":"GUILD_MEMBER_ADD","d":{"guild_id":2,"user":{"id":7,"username":"me"},"roles":[],"pending":false}}""")
        val removed = parser.parse("""{"op":0,"t":"GUILD_MEMBER_REMOVE","d":{"guild_id":2,"user":{"id":7,"username":"me"}}}""")
        val chunk = parser.parse("""{"op":0,"t":"GUILD_MEMBERS_CHUNK","d":{"guild_id":2,"members":[],"presences":[],"not_found":[]}}""")
        val role = parser.parse("""{"op":0,"t":"GUILD_ROLE_CREATE","d":{"guild_id":2,"role_id":3,"role":{"id":3,"name":"role","permissions":1024}}}""")
        val roleDelete = parser.parse("""{"op":0,"t":"GUILD_ROLE_DELETE","d":{"guild_id":2,"role_id":3}}""")
        assertEquals(2L, (member.data as GuildMember).guildId)
        assertEquals(7L, (removed.data as GuildMemberRemove).user.id)
        assertTrue((chunk.data as GuildMembersChunk).members.orEmpty().isEmpty())
        assertEquals(3L, (role.data as GuildRoleCreate).role?.id)
        assertEquals(3L, (roleDelete.data as GuildRoleDelete).roleId)
        val memberList = parser.parse("""{"op":0,"t":"GUILD_MEMBER_LIST_UPDATE","d":{"guild_id":2,"id":"everyone","groups":[{"id":"online","count":1}],"online_count":1,"member_count":1,"ops":[{"op":"SYNC","range":[0,1],"items":[{"group":{"id":"online","count":1}},{"member":{"guild_id":2,"user":{"id":7,"username":"me","discriminator":"0000"},"roles":[],"pending":false}}]}]}}""")
        assertTrue((memberList.data as ModelGuildMemberListUpdate).ops.single() is ModelGuildMemberListUpdate.Operation.Sync)

        // The reference streams the frame and calls `reader.skipValue()` for a dispatch type it has
        // no branch for, leaving `Incoming.data` untouched. It never retains the raw JSON, so an
        // unknown event yields the "nothing to route" marker rather than a JsonElement.
        val unknown = parser.parse("{\"op\":0,\"s\":4,\"t\":\"FUTURE_EVENT\",\"d\":{\"value\":1}}")
        assertSame(IncomingParser.DISPATCH_UNPARSED, unknown.data)
        assertEquals("FUTURE_EVENT", unknown.type)

        val incrementals = JsonParser.parseString(
            requireNotNull(javaClass.getResource("/fixtures/gateway/incremental_core.json")).readText(),
        ).asJsonArray.map { parser.parse(it.toString()) }
        assertTrue(incrementals.single { it.type == "THREAD_LIST_SYNC" }.data is ModelThreadListSync)
        assertSame(
            IncomingParser.DISPATCH_UNPARSED,
            incrementals.single { it.type == "FUTURE_EVENT" }.data,
        )
    }

    private fun stageEvent(type: String, topic: String) =
        "{\"op\":0,\"s\":4,\"t\":\"$type\",\"d\":{\"id\":1,\"guild_id\":2,\"channel_id\":3,\"topic\":\"$topic\",\"privacy_level\":2,\"invite_code\":null}}"

    private fun memoryPreferences(): SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        lateinit var editor: Any
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> editor.also { values[args[0] as String] = args[1] }
                "remove" -> editor.also { values.remove(args[0] as String) }
                "apply" -> null
                else -> editor
            }
        }
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0]] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }
}
