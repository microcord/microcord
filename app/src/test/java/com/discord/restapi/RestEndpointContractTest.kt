package com.discord.restapi

import com.discord.api.presence.ClientStatus
import com.discord.stores.requestUserGuildSettings
import com.google.gson.JsonParser
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract for the endpoint families outside the message-send path: guild and channel CRUD,
 * members and roles, threads, invites, and account settings.
 *
 * Every assertion checks the three things a Retrofit declaration can silently get wrong — the HTTP
 * verb, the resolved path (including query parameters), and the serialized body. Fixtures are
 * synthetic: the ids are small integers and no fixture carries a token, password or real user
 * content.
 */
class RestEndpointContractTest {
    private val server = MockWebServer()

    private val api by lazy {
        RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java)
    }

    /** The reference sends channel reordering through the null-serializing client; see below. */
    private val nullSerializingApi by lazy {
        RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java, serializeNulls = true)
    }

    @After
    fun stopServer() = server.shutdown()

    // -- Guild CRUD ---------------------------------------------------------------------------

    @Test
    fun privateChannelNotificationOverrideUsesExactEndpointAndPayload() {
        enqueueObject()
        requestUserGuildSettings(
            api,
            0L,
            RestAPIParams.UserGuildSettings(
                42L,
                RestAPIParams.UserGuildSettings.ChannelOverride(muted = true, muteConfig = null),
            ),
        ).toBlocking().first()

        val body = json(takeRequest("PATCH", "/api/v9/users/@me/guilds/@me/settings"))
        assertEquals(setOf("channel_overrides"), body.keySet())
        val override = body["channel_overrides"].asJsonObject["42"].asJsonObject
        assertEquals(setOf("muted"), override.keySet())
        assertTrue(override["muted"].asBoolean)
    }

    // -- Channel CRUD -------------------------------------------------------------------------

    @Test
    fun permissionOverwriteUsesStringIdAndNumericTargetType() {
        enqueueNoContent()
        api.updatePermissionOverwrites(
            2,
            3,
            RestAPIParams.ChannelPermissionOverwrites.createForRole(3, allow = 1024, deny = 2048),
        ).toBlocking().subscribe()

        val request = takeRequest("PUT", "/api/v9/channels/2/permissions/3")
        val body = json(request)
        assertEquals(0, body["type"].asInt)
        assertEquals("3", body["id"].asString)
        assertEquals(1024L, body["allow"].asLong)
        assertEquals(2048L, body["deny"].asLong)

        enqueueNoContent()
        api.updatePermissionOverwrites(
            2,
            4,
            RestAPIParams.ChannelPermissionOverwrites.createForMember(4, allow = null, deny = 8),
        ).toBlocking().subscribe()
        val member = json(takeRequest("PUT", "/api/v9/channels/2/permissions/4"))
        assertEquals(1, member["type"].asInt)
        assertFalse(member.has("allow"))
    }

    /**
     * A reorder must be able to say "move this channel out of its category" (an explicit JSON null)
     * without also clearing the parent of channels that merely moved within one, which is why
     * [RestAPIParams.ChannelPosition] carries its own serializer.
     */
    @Test
    fun channelReorderDistinguishesUnsetParentFromClearedParent() {
        enqueueNoContent()
        nullSerializingApi.reorderChannels(
            1,
            listOf(
                RestAPIParams.ChannelPosition(id = 5, position = 2, parentId = null),
                RestAPIParams.ChannelPosition(
                    id = 6,
                    position = 0,
                    parentId = RestAPIParams.ChannelPosition.PARENT_ID_NONE,
                ),
                RestAPIParams.ChannelPosition(id = 7, position = 1, parentId = 9),
            ),
        ).toBlocking().subscribe()

        val request = takeRequest("PATCH", "/api/v9/guilds/1/channels")
        val positions = JsonParser.parseString(request.body.readUtf8()).asJsonArray
        assertFalse(positions[0].asJsonObject.has("parent_id"))
        assertTrue(positions[0].asJsonObject["id"].asJsonPrimitive.isNumber)
        assertEquals(2, positions[0].asJsonObject["position"].asInt)
        assertTrue(positions[1].asJsonObject["parent_id"].isJsonNull)
        assertEquals(9L, positions[2].asJsonObject["parent_id"].asLong)
        assertTrue(positions[2].asJsonObject["parent_id"].asJsonPrimitive.isNumber)
    }

    // -- Members and roles ----------------------------------------------------------------------

    // -- Threads ----------------------------------------------------------------------------------

    // -- Invites -------------------------------------------------------------------------------------

    // -- Search ----------------------------------------------------------------------------------------------

    @Test
    fun messageSearchRepeatsMultiValueFiltersAndSkipsAbsentOnes() {
        enqueueObject()
        api.searchChannelMessages(
            channelId = 2,
            oldestMessageId = 10,
            authorIds = listOf("3", "4"),
            mentionsIds = null,
            has = listOf("link"),
            content = null,
            attempts = null,
            includeNsfw = true,
        ).toBlocking().first()

        val request = takeRequest(
            "GET",
            "/api/v9/channels/2/messages/search?max_id=10&author_id=3&author_id=4&has=link&include_nsfw=true",
        )
        assertNull(request.getHeader(RestAPIInterface.X_CONTEXT_PROPERTIES))
    }

    // -- Helpers ---------------------------------------------------------------------------------------------------

    private fun enqueueObject() = server.enqueue(MockResponse().setBody("{}"))

    private fun enqueueList() = server.enqueue(MockResponse().setBody("[]"))

    private fun enqueueNoContent() = server.enqueue(MockResponse().setResponseCode(204))

    private fun takeRequest(method: String, path: String): RecordedRequest {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        return request
    }

    private fun json(request: RecordedRequest) =
        JsonParser.parseString(request.body.readUtf8()).asJsonObject
}
