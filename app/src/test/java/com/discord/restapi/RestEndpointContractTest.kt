package com.discord.restapi

import com.discord.api.presence.ClientStatus
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
    fun defaultGuildCreationMatchesCreateScreenPayload() {
        enqueueObject()
        api.createGuild(
            RestAPIParams.CreateGuild(
                name = "Fixture Guild",
                icon = "data:image/png;base64,fixture",
                channels = emptyList(),
                systemChannelId = 11L,
            ),
        ).toBlocking().first()

        val body = json(takeRequest("POST", "/api/v9/guilds"))
        assertEquals(setOf("name", "icon", "channels", "system_channel_id"), body.keySet())
        assertEquals("Fixture Guild", body["name"].asString)
        assertEquals("data:image/png;base64,fixture", body["icon"].asString)
        assertTrue(body["channels"].asJsonArray.isEmpty)
        assertEquals(11L, body["system_channel_id"].asLong)
    }

    @Test
    fun guildCreateReadUpdateDeleteMatchWireContract() {
        enqueueObject()
        api.createGuild(
            RestAPIParams.CreateGuild(
                name = "Fixture Guild",
                icon = null,
                channels = listOf(
                    RestAPIParams.CreateGuildChannel(
                        type = 0,
                        id = null,
                        name = "general",
                        parentId = null,
                        permissionOverwrites = null,
                        topic = null,
                    ),
                ),
            ),
        ).toBlocking().first()
        val created = takeRequest("POST", "/api/v9/guilds")
        val createdBody = json(created)
        assertEquals("Fixture Guild", createdBody["name"].asString)
        assertFalse(createdBody.has("icon"))
        assertEquals("general", createdBody["channels"].asJsonArray[0].asJsonObject["name"].asString)

        enqueueList()
        api.getBans(1).toBlocking().first()
        takeRequest("GET", "/api/v9/guilds/1/bans")

        enqueueObject()
        api.updateGuild(1, RestAPIParams.UpdateGuild(name = "Renamed")).toBlocking().first()
        val updated = takeRequest("PATCH", "/api/v9/guilds/1")
        assertEquals("{\"name\":\"Renamed\"}", updated.body.readUtf8())

        enqueueNoContent()
        api.deleteGuild(1, RestAPIParams.DeleteGuild(null)).toBlocking().subscribe()
        val deleted = takeRequest("POST", "/api/v9/guilds/1/delete")
        assertEquals("{}", deleted.body.readUtf8())
    }

    @Test
    fun leavingAGuildAsLurkerSendsADeleteWithABody() {
        enqueueNoContent()
        api.leaveGuild(1, RestAPIParams.LeaveGuildBody(lurking = true)).toBlocking().subscribe()

        val request = takeRequest("DELETE", "/api/v9/users/@me/guilds/1")
        assertEquals("{\"lurking\":true}", request.body.readUtf8())
    }

    // -- Channel CRUD -------------------------------------------------------------------------

    @Test
    fun channelCreateEditAndDeleteMatchWireContract() {
        enqueueObject()
        api.createGuildChannel(
            1,
            RestAPIParams.CreateGuildChannel(
                type = 0,
                id = null,
                name = "general",
                parentId = 9,
                permissionOverwrites = null,
                topic = null,
            ),
        ).toBlocking().first()
        val created = takeRequest("POST", "/api/v9/guilds/1/channels")
        val createdBody = json(created)
        assertEquals(0, createdBody["type"].asInt)
        assertEquals(9L, createdBody["parent_id"].asLong)

        enqueueObject()
        api.editTextChannel(
            2,
            RestAPIParams.TextChannel(
                name = "renamed",
                type = null,
                topic = "topic",
                nsfw = false,
                rateLimitPerUser = 5,
                defaultAutoArchiveDuration = null,
            ),
        ).toBlocking().first()
        val edited = takeRequest("PATCH", "/api/v9/channels/2")
        val editedBody = json(edited)
        assertEquals("renamed", editedBody["name"].asString)
        assertEquals(5, editedBody["rate_limit_per_user"].asInt)
        assertFalse(editedBody.has("type"))

        enqueueObject()
        api.deleteChannel(2).toBlocking().first()
        takeRequest("DELETE", "/api/v9/channels/2")
    }

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
        assertEquals(2, positions[0].asJsonObject["position"].asInt)
        assertTrue(positions[1].asJsonObject["parent_id"].isJsonNull)
        assertEquals(9L, positions[2].asJsonObject["parent_id"].asLong)
    }

    // -- Members and roles ----------------------------------------------------------------------

    @Test
    fun memberEditsCarryTheirAuditLogReason() {
        enqueueNoContent()
        api.changeGuildNickname(1, RestAPIParams.Nick("fixture")).toBlocking().subscribe()
        val nick = takeRequest("PATCH", "/api/v9/guilds/1/members/@me/nick")
        assertEquals("{\"nick\":\"fixture\"}", nick.body.readUtf8())

        enqueueNoContent()
        api.banGuildMember(1, 5, RestAPIParams.BanGuildMember(deleteMessageDays = 7), "raid")
            .toBlocking().subscribe()
        val ban = takeRequest("PUT", "/api/v9/guilds/1/bans/5")
        assertEquals("raid", ban.getHeader(RestAPIInterface.X_AUDIT_LOG_REASON))
        assertEquals(7, json(ban)["delete_message_days"].asInt)

        enqueueNoContent()
        api.kickGuildMember(1, 5, "inactive").toBlocking().subscribe()
        val kick = takeRequest("DELETE", "/api/v9/guilds/1/members/5")
        assertEquals("inactive", kick.getHeader(RestAPIInterface.X_AUDIT_LOG_REASON))

        enqueueNoContent()
        api.changeGuildMember(1, 5, RestAPIParams.GuildMember.createWithRoles(listOf(11, 12)))
            .toBlocking().subscribe()
        val roles = json(takeRequest("PATCH", "/api/v9/guilds/1/members/5"))
        assertEquals(2, roles["roles"].asJsonArray.size())
        assertFalse(roles.has("nick"))
    }

    @Test
    fun roleCreateUpdateAndReorderMatchWireContract() {
        enqueueObject()
        api.createRole(1).toBlocking().first()
        takeRequest("POST", "/api/v9/guilds/1/roles")

        enqueueNoContent()
        api.updateRole(1, 4, RestAPIParams.Role(name = "Moderator", color = 255, permissions = 8))
            .toBlocking().subscribe()
        val updated = json(takeRequest("PATCH", "/api/v9/guilds/1/roles/4"))
        assertEquals("Moderator", updated["name"].asString)
        assertEquals(255, updated["color"].asInt)
        assertEquals(8L, updated["permissions"].asLong)

        enqueueNoContent()
        api.batchUpdateRole(
            1,
            listOf(
                RestAPIParams.Role.createForPosition(4, 1),
                RestAPIParams.Role.createForPosition(5, 2),
            ),
        ).toBlocking().subscribe()
        val batch = JsonParser.parseString(
            takeRequest("PATCH", "/api/v9/guilds/1/roles").body.readUtf8(),
        ).asJsonArray
        assertEquals(4L, batch[0].asJsonObject["id"].asLong)
        assertEquals(2, batch[1].asJsonObject["position"].asInt)

        enqueueNoContent()
        api.deleteRole(1, 4).toBlocking().subscribe()
        takeRequest("DELETE", "/api/v9/guilds/1/roles/4")
    }

    // -- Threads ----------------------------------------------------------------------------------

    @Test
    fun threadLifecycleCarriesLocationAndArchiveWindow() {
        enqueueObject()
        api.createThread(
            2,
            "Chat Input Blocker",
            RestAPIParams.ThreadCreationSettings(name = "topic", type = 11, autoArchiveDuration = 1440),
        ).toBlocking().first()
        val created = takeRequest("POST", "/api/v9/channels/2/threads?location=Chat%20Input%20Blocker")
        val body = json(created)
        assertEquals("topic", body["name"].asString)
        assertEquals(11, body["type"].asInt)
        assertEquals(1440, body["auto_archive_duration"].asInt)

        enqueueNoContent()
        api.joinThread(3, "Thread Header", RestAPIParams.EmptyBody()).toBlocking().subscribe()
        takeRequest("POST", "/api/v9/channels/3/thread-members/@me?location=Thread%20Header")

        enqueueNoContent()
        api.leaveThread(3, "Thread Header").toBlocking().subscribe()
        takeRequest("DELETE", "/api/v9/channels/3/thread-members/@me?location=Thread%20Header")

        enqueueObject()
        api.updateThreadMemberSettings(3, RestAPIParams.ThreadMemberSettings(muted = true))
            .toBlocking().first()
        val settings = takeRequest("PATCH", "/api/v9/channels/3/thread-members/@me/settings")
        assertEquals("{\"muted\":true}", settings.body.readUtf8())

        enqueueObject()
        api.getAllPublicArchivedThreads(2, "900").toBlocking().first()
        takeRequest("GET", "/api/v9/channels/2/threads/archived/public?before=900")

        enqueueObject()
        api.editThread(3, RestAPIParams.ThreadSettings(archived = true, locked = false))
            .toBlocking().first()
        val edited = json(takeRequest("PATCH", "/api/v9/channels/3"))
        assertTrue(edited["archived"].asBoolean)
        assertFalse(edited["locked"].asBoolean)
    }

    // -- Invites -------------------------------------------------------------------------------------

    @Test
    fun inviteCreateResolveAndRevokeMatchWireContract() {
        enqueueObject()
        api.postChannelInvite(
            2,
            RestAPIParams.Invite(maxAge = 86400, maxUses = 0, temporary = false, regenerate = null),
        ).toBlocking().first()
        val created = takeRequest("POST", "/api/v9/channels/2/invites")
        val body = json(created)
        assertEquals(86400, body["max_age"].asInt)
        assertEquals(0, body["max_uses"].asInt)
        assertFalse(body["temporary"].asBoolean)
        assertFalse(body.has("regenerate"))

        enqueueObject()
        api.getInviteCode("fixture-code", withCounts = true).toBlocking().first()
        takeRequest("GET", "/api/v9/invites/fixture-code?with_counts=true")

        enqueueObject()
        api.getInviteCode("fixture-code", withCounts = false, guildScheduledEventId = 12)
            .toBlocking().first()
        takeRequest("GET", "/api/v9/invites/fixture-code?with_counts=false&guild_scheduled_event_id=12")

        enqueueObject()
        api.revokeInvite("fixture-code").toBlocking().first()
        takeRequest("DELETE", "/api/v9/invites/fixture-code")
    }

    // -- Account settings ------------------------------------------------------------------------------

    @Test
    fun userSettingsPatchesCarryExactlyOneChangedValue() {
        enqueueObject()
        api.updateUserSettings(RestAPIParams.UserSettings.createWithTheme("dark")).toBlocking().first()
        val theme = takeRequest("PATCH", "/api/v9/users/@me/settings")
        assertEquals("{\"theme\":\"dark\"}", theme.body.readUtf8())

        enqueueObject()
        api.updateUserSettings(RestAPIParams.UserSettings.createWithStatus(ClientStatus.DND))
            .toBlocking().first()
        assertEquals(
            "{\"status\":\"dnd\"}",
            takeRequest("PATCH", "/api/v9/users/@me/settings").body.readUtf8(),
        )

        enqueueObject()
        api.updateUserSettings(RestAPIParams.UserSettings.createWithFriendSourceFlags(true, null, null))
            .toBlocking().first()
        val flags = json(takeRequest("PATCH", "/api/v9/users/@me/settings"))
        assertEquals(setOf("friend_source_flags"), flags.keySet())
        assertTrue(flags["friend_source_flags"].asJsonObject["all"].asBoolean)
    }

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
