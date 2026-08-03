package com.discord.restapi

import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.api.friendsuggestions.FriendSuggestionDelete
import com.discord.api.presence.ClientStatus
import com.discord.gateway.io.IncomingParser
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelUserNote
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialTransportContractTest {
    private val server = MockWebServer()
    private val api = RestAPIBuilder(server.url("/api/").toString(), EmptyCookieJar).build(RestAPIInterface::class.java)

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun socialRoutesPreservePathsBodiesHeadersAndExplicitClear() {
        enqueue("{\"id\":7,\"username\":\"fixture\"}")
        api.userGet(7).toBlocking().single()
        assertRequest("GET", "/api/v9/users/7")

        enqueue("{}")
        api.userProfileGet(7, true, 9).toBlocking().single()
        assertRequest("GET", "/api/v9/users/7/profile?with_mutual_guilds=true&guild_id=9")

        enqueue(
            """{"items":[{"sku_id":"4","type":1,"animationType":1,"effects":[{"src":"https://cdn.example/layer","loop":true,"loopDelay":5000,"zIndex":2}]}]}""",
        )
        val effectProduct = api.getCollectiblesProduct(4, "en-US").toBlocking().single()
        assertEquals(5000L, effectProduct.items.single().effects.single().loopDelay)
        assertRequest("GET", "/api/v9/collectibles-products/4?locale=en-US")

        enqueue("[]")
        api.getRelationships(7).toBlocking().single()
        assertRequest("GET", "/api/v9/users/7/relationships")

        enqueue204()
        api.addRelationship(7, RestAPIParams.UserRelationship(1, "friend"), "put-context")
            .toBlocking().subscribe()
        assertRequest(
            "PUT",
            "/api/v9/users/@me/relationships/7",
            "{\"type\":1,\"friend_token\":\"friend\"}",
            "put-context",
        )

        enqueue204()
        api.removeRelationship(7, "delete-context").toBlocking().subscribe()
        assertRequest("DELETE", "/api/v9/users/@me/relationships/7", context = "delete-context")

        enqueue204()
        api.sendRelationshipRequest(RestAPIParams.UserRelationship.Add("fixture", 42), "post-context")
            .toBlocking().subscribe()
        assertRequest(
            "POST",
            "/api/v9/users/@me/relationships",
            "{\"username\":\"fixture\",\"discriminator\":42}",
            "post-context",
        )

        enqueue("{\"successful_requests\":[],\"failed_requests\":[]}")
        api.bulkAddRelationships(RestAPIParams.UserBulkRelationship(listOf(7, 8), "bulk-token"))
            .toBlocking().single()
        assertRequest(
            "POST",
            "/api/v9/users/@me/relationships/bulk",
            "{\"user_ids\":[7,8],\"token\":\"bulk-token\"}",
        )

        enqueue("{\"note_user_id\":7,\"note\":\"hello\"}")
        api.getUserNote(7).toBlocking().single()
        assertRequest("GET", "/api/v9/users/@me/notes/7")

        enqueue204()
        api.updateUserNotes(7, RestAPIParams.UserNoteUpdate("updated")).toBlocking().subscribe()
        assertRequest("PUT", "/api/v9/users/@me/notes/7", "{\"note\":\"updated\"}")

        enqueue("[]")
        api.getFriendSuggestions().toBlocking().single()
        assertRequest("GET", "/api/v9/friend-suggestions")

        enqueue204()
        api.ignoreFriendSuggestion(7).toBlocking().subscribe()
        assertRequest("DELETE", "/api/v9/friend-suggestions/7")

        enqueue("{\"user_affinities\":[],\"inverse_user_affinities\":[]}")
        api.getUserAffinities().toBlocking().single()
        assertRequest("GET", "/api/v9/users/@me/affinities/users")

        enqueue("{\"id\":1,\"type\":1}")
        api.userCreateChannel(RestAPIParams.CreateChannel(7)).toBlocking().single()
        assertRequest("POST", "/api/v9/users/@me/channels", "{\"recipients\":[7]}")

        enqueue("{}")
        api.updateUserSettingsStatus(RestAPIParams.UserSettingsStatus(ClientStatus.DND)).toBlocking().single()
        assertRequest("PATCH", "/api/v9/users/@me/settings", "{\"status\":\"dnd\"}")

        enqueue("{}")
        api.updateUserSettingsCustomStatus(
            RestAPIParams.UserSettingsCustomStatus.set(ModelCustomStatusSetting("busy", null, null, null)),
        ).toBlocking().single()
        assertRequest("PATCH", "/api/v9/users/@me/settings", "{\"custom_status\":{\"text\":\"busy\"}}")

        enqueue("{}")
        val clear = RestAPIParams.UserSettingsCustomStatus.clear()
        assertEquals(
            "{\"custom_status\":null}",
            GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerDiscordApiTypeAdapters().create().toJson(clear),
        )
        api.updateUserSettingsCustomStatus(clear).toBlocking().single()
        assertRequest("PATCH", "/api/v9/users/@me/settings", "{\"custom_status\":null}")
    }

    @Test
    fun incomingSocialEventsUseTypedPayloads() {
        val parser = IncomingParser(
            GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerDiscordApiTypeAdapters().create(),
        )

        val created = parser.parse(
            "{\"op\":0,\"t\":\"FRIEND_SUGGESTION_CREATE\",\"d\":{" +
                "\"suggested_user\":{\"id\":7,\"username\":\"fixture\"}," +
                "\"reasons\":[{\"type\":1,\"platform_type\":\"contacts\",\"name\":null}]}}",
        ).data
        val deleted = parser.parse(
            "{\"op\":0,\"t\":\"FRIEND_SUGGESTION_DELETE\",\"d\":{\"suggested_user_id\":7}}",
        ).data
        val note = parser.parse(
            "{\"op\":0,\"t\":\"USER_NOTE_UPDATE\",\"d\":{\"id\":7,\"note\":\"hello\"}}",
        ).data

        assertTrue(created is FriendSuggestion)
        assertEquals(1, (created as FriendSuggestion).reasons.single().type.apiValue)
        assertEquals(7, (deleted as FriendSuggestionDelete).suggestedUserId)
        assertEquals(ModelUserNote.Update(7, "hello"), note)
    }

    private fun enqueue(body: String) = server.enqueue(MockResponse().setBody(body))
    private fun enqueue204() = server.enqueue(MockResponse().setResponseCode(204))

    private fun assertRequest(method: String, path: String, body: String = "", context: String? = null) {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals(body, request.body.readUtf8())
        assertEquals(context, request.getHeader("X-Context-Properties"))
    }

    private object EmptyCookieJar : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }
}
