package com.discord.restapi

import com.discord.api.savedmessage.SavedMessage
import com.discord.api.savedmessage.SavedMessageDelete
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.gateway.io.IncomingParser
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.rest.RestAPI
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SavedMessagesTransportTest {
    private val server = MockWebServer()
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    @After
    fun tearDown() {
        RestAPI.reset()
        server.shutdown()
    }

    @Test
    fun listAndRemoveUseDefaultMetadataWithoutRequestBodies() {
        server.enqueue(MockResponse().setBody("{\"results\":[$SAVED_MESSAGE]}"))
        server.enqueue(MockResponse().setResponseCode(204))
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), EmptyCookieJar), emptyList())

        val saved = RestAPI.api.getSavedMessages().toBlocking().single().results.single()
        RestAPI.api.deleteSavedMessage(10, 20).toBlocking().subscribe()
        assertEquals(20L, saved.message!!.id)
        assertEquals(10L, saved.saveData.channelId)
        assertEquals(20L, saved.saveData.messageId)
        assertEquals(UtcDateTime(1704067200000L), saved.saveData.savedAt)
        assertEquals("author summary fixture", saved.saveData.authorSummary)
        assertEquals("channel summary fixture", saved.saveData.channelSummary)
        assertEquals("message summary fixture", saved.saveData.messageSummary)
        assertEquals("notes fixture", saved.saveData.notes)
        assertEquals(7L, saved.saveData.authorId)
        assertNull(saved.saveData.guildId)
        assertNull(saved.saveData.dueAt)

        val requests = List(2) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        requests.forEach { request ->
            assertEquals(AnalyticSuperProperties.USER_AGENT, request.getHeader("User-Agent"))
            assertEquals(AnalyticSuperProperties.superPropertiesStringBase64, request.getHeader("X-Super-Properties"))
            assertEquals(TimeZone.getDefault().id, request.getHeader("X-Discord-Timezone"))
        }
        assertEquals(listOf("GET", "DELETE"), requests.map { it.method })
        assertEquals("/api/v9/users/@me/saved-messages", requests[0].path)
        assertEquals("/api/v9/users/@me/saved-messages/10/20", requests[1].path)
        assertEquals("", requests[0].body.readUtf8())
        assertEquals("", requests[1].body.readUtf8())
    }

    @Test
    fun gatewayCreateUsesSavedMessageEnvelopeAndDeleteUsesTypedIds() {
        val parser = IncomingParser(gson)
        val created = parser.parse("{\"op\":0,\"t\":\"SAVED_MESSAGE_CREATE\",\"s\":1,\"d\":$SAVED_MESSAGE}").data
        assertTrue(created is SavedMessage)
        val saved = created as SavedMessage
        val message = checkNotNull(saved.message)
        assertEquals(20L, message.id)
        assertEquals("message fixture", message.content)
        assertEquals(20L, saved.saveData.messageId)

        val fallback = parser.parse("{\"d\":$SAVED_MESSAGE,\"s\":2,\"t\":\"SAVED_MESSAGE_CREATE\",\"op\":0}").data
        assertEquals(saved, fallback)
        val deleted = parser.parse(
            """{"op":0,"t":"SAVED_MESSAGE_DELETE","s":3,"d":{"channel_id":"10","message_id":"20"}}""",
        ).data
        assertEquals(SavedMessageDelete(10, 20), deleted)
    }

    @Test
    fun unavailableMessagesAndRemindersKeepAllSaveDataAndRedactSummaries() {
        val json = gson.fromJson(SAVED_MESSAGE, com.google.gson.JsonObject::class.java)
        json.add("message", com.google.gson.JsonNull.INSTANCE)
        json.getAsJsonObject("save_data").apply {
            addProperty("due_at", "2024-01-02T00:00:00Z")
            addProperty("guild_id", "30")
            addProperty("author_id", 0)
        }
        val saved = gson.fromJson(json, SavedMessage::class.java)
        assertNull(saved.message)
        assertEquals(UtcDateTime(1704153600000L), saved.saveData.dueAt)
        assertEquals(30L, saved.saveData.guildId)
        assertEquals(0L, saved.saveData.authorId)
        assertEquals("notes fixture", saved.saveData.notes)
        listOf("author summary fixture", "channel summary fixture", "message summary fixture", "notes fixture")
            .forEach { assertFalse(saved.toString().contains(it)) }
    }

    private object EmptyCookieJar : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    private companion object {
        const val SAVED_MESSAGE = """{
            "message":{"id":"20","channel_id":"10","author":{"id":"7","username":"fixture"},"content":"message fixture"},
            "save_data":{
                "channel_id":"10","message_id":"20","saved_at":"2024-01-01T00:00:00Z",
                "author_summary":"author summary fixture","channel_summary":"channel summary fixture",
                "message_summary":"message summary fixture","author_id":"7","notes":"notes fixture","due_at":null
            }
        }"""
    }
}
