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
