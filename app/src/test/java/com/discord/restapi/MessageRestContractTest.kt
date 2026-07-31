package com.discord.restapi

import com.discord.api.message.activity.MessageActivityType
import com.discord.restapi.utils.CountingRequestBody
import com.google.gson.JsonParser
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class MessageRestContractTest {
    private val server = MockWebServer()
    private val api by lazy {
        RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java)
    }

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun messageQueriesUseExpectedParameters() {
        enqueueList()
        enqueueList()

        api.getChannelMessages(42, before = 100, after = 20, limit = 50).toBlocking().single()
        api.getChannelMessagesAround(42, limit = 25, around = 60).toBlocking().single()

        assertEquals("/api/v9/channels/42/messages?before=100&after=20&limit=50", server.takeRequest().path)
        assertEquals("/api/v9/channels/42/messages?limit=25&around=60", server.takeRequest().path)
    }

    @Test
    fun sendEditDeleteTypingAndAckMatchWireContract() {
        val send = RestAPIParams.Message(
            content = "hello",
            nonce = "nonce-1",
            applicationId = 9,
            activity = RestAPIParams.Message.Activity(MessageActivityType.JOIN, "party", "session"),
            stickerIds = listOf(7),
            messageReference = RestAPIParams.Message.MessageReference(1, 2, 3),
            allowedMentions = RestAPIParams.Message.AllowedMentions(
                parse = listOf("users"),
                users = listOf(4),
                roles = listOf(5),
                repliedUser = false,
            ),
            captchaKey = "captcha",
            captchaRqtoken = "rqtoken",
            enforceNonce = true,
        )
        enqueueMessage()
        api.sendMessage(42, send).toBlocking().single()

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/v9/channels/42/messages", sent.path)
        val json = JsonParser.parseString(sent.body.readUtf8()).asJsonObject
        assertEquals("hello", json["content"].asString)
        assertEquals(9L, json["application_id"].asLong)
        assertEquals(1, json["activity"].asJsonObject["type"].asInt)
        assertEquals("captcha", json["captcha_key"].asString)
        assertEquals("rqtoken", json["captcha_rqtoken"].asString)
        assertEquals(3L, json["message_reference"].asJsonObject["message_id"].asLong)
        assertFalse(json["allowed_mentions"].asJsonObject["replied_user"].asBoolean)
        assertFalse(json.has("enforce_nonce"))

        enqueueMessage()
        api.editMessage(
            42,
            8,
            RestAPIParams.Message(
                content = "edited",
                allowedMentions = RestAPIParams.Message.AllowedMentions(parse = emptyList()),
            ),
        ).toBlocking().single()
        val edited = server.takeRequest()
        assertEquals("PATCH", edited.method)
        assertEquals("/api/v9/channels/42/messages/8", edited.path)
        val editJson = JsonParser.parseString(edited.body.readUtf8()).asJsonObject
        assertEquals(setOf("content", "allowed_mentions"), editJson.keySet())
        assertEquals(0, editJson["allowed_mentions"].asJsonObject["parse"].asJsonArray.size())

        enqueueEmpty()
        api.deleteMessage(42, 8).toBlocking().subscribe()
        assertRequest("DELETE", "/api/v9/channels/42/messages/8")

        enqueueEmpty()
        api.setUserTyping(42).toBlocking().subscribe()
        val typing = server.takeRequest()
        assertEquals("POST", typing.method)
        assertEquals("/api/v9/channels/42/typing", typing.path)
        assertEquals("{}", typing.body.readUtf8())

        enqueueEmpty()
        api.postChannelMessagesAck(42, 8, RestAPIParams.ChannelMessagesAck(true, 2)).toBlocking().subscribe()
        val ack = server.takeRequest()
        assertEquals("POST", ack.method)
        assertEquals("/api/v9/channels/42/messages/8/ack", ack.path)
        assertEquals("{\"manual\":true,\"mention_count\":2}", ack.body.readUtf8())
    }

    @Test
    fun multipartUsesPayloadJsonAndIndexedFileNames() {
        val file = MultipartBody.Part.createFormData(
            "files[0]",
            "note.txt",
            "attachment-data".toRequestBody("text/plain".toMediaType()),
        )
        enqueueMessage()

        api.sendMessage(
            42,
            PayloadJSON(RestAPIParams.Message(content = "with file", nonce = "n")),
            arrayOf(file),
        ).toBlocking().single()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v9/channels/42/messages", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"payload_json\""))
        assertTrue(body.contains("{\"content\":\"with file\",\"nonce\":\"n\"}"))
        assertTrue(body.contains("name=\"files[0]\"; filename=\"note.txt\""))
        assertTrue(body.contains("attachment-data"))
    }

    @Test
    fun pinsAndReactionsMatchPathsAndMethods() {
        enqueueList()
        api.getChannelPins(42).toBlocking().single()
        assertRequest("GET", "/api/v9/channels/42/pins")

        enqueueEmpty()
        api.addChannelPin(42, 8).toBlocking().subscribe()
        assertRequest("PUT", "/api/v9/channels/42/pins/8")

        enqueueEmpty()
        api.deleteChannelPin(42, 8).toBlocking().subscribe()
        assertRequest("DELETE", "/api/v9/channels/42/pins/8")

        val reaction = "%F0%9F%91%8D"
        enqueueEmpty()
        api.addReaction(42, 8, reaction).toBlocking().subscribe()
        assertRequest("PUT", "/api/v9/channels/42/messages/8/reactions/$reaction/@me")

        enqueueEmpty()
        api.removeSelfReaction(42, 8, reaction).toBlocking().subscribe()
        assertRequest("DELETE", "/api/v9/channels/42/messages/8/reactions/$reaction/@me")

        enqueueEmpty()
        api.removeReaction(42, 8, reaction, 9).toBlocking().subscribe()
        assertRequest("DELETE", "/api/v9/channels/42/messages/8/reactions/$reaction/9")

        enqueueEmpty()
        api.removeAllReactions(42, 8).toBlocking().subscribe()
        assertRequest("DELETE", "/api/v9/channels/42/messages/8/reactions")

        enqueueList()
        api.getReactionUsers(42, 8, reaction, 25).toBlocking().single()
        assertRequest("GET", "/api/v9/channels/42/messages/8/reactions/$reaction?limit=25")
    }

    @Test
    fun reactionEmptySuccessInvokesCallbacksAndRealErrorsStillFail() {
        val reaction = "%F0%9F%91%8D"
        var updates = 0
        var failure: Throwable? = null

        enqueueEmpty()
        api.addReaction(42, 8, reaction).subscribe({ updates++ }, { failure = it })
        enqueueEmpty()
        api.removeSelfReaction(42, 8, reaction).subscribe({ updates++ }, { failure = it })

        assertEquals(2, updates)
        assertNull(failure)

        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"message\":\"bad reaction\"}"))
        api.addReaction(42, 8, reaction).subscribe({ updates++ }, { failure = it })

        assertEquals(2, updates)
        assertTrue(failure is HttpException)
    }

    @Test
    fun emptySuccessHandlingDoesNotHideJsonErrors() {
        var usersReceived = false
        var failure: Throwable? = null
        server.enqueue(MockResponse().setBody("["))

        api.getReactionUsers(42, 8, "%F0%9F%91%8D", 25)
            .subscribe({ usersReceived = true }, { failure = it })

        assertFalse(usersReceived)
        assertTrue(failure != null)
    }

    @Test
    fun mentionsUseReferenceFiltersAndPagination() {
        enqueueList()
        api.getMentions(25, roles = true, everyone = true, guildId = 7, before = 9).toBlocking().single()
        assertRequest("GET", "/api/v9/users/@me/mentions?limit=25&roles=true&everyone=true&guild_id=7&before=9")
    }

    @Test
    fun countingBodyReportsBytesWritten() {
        val body = CountingRequestBody("upload".toRequestBody("text/plain".toMediaType()))
        val progress = mutableListOf<Long>()
        body.bytesWrittenObservable.subscribe(progress::add)

        body.writeTo(Buffer())

        assertEquals(6L, body.estimatedContentLength)
        assertEquals(listOf(0L, 6L), progress)
    }

    private fun enqueueMessage() = server.enqueue(MockResponse().setBody("{\"id\":1}"))

    private fun enqueueList() = server.enqueue(MockResponse().setBody("[]"))

    private fun enqueueEmpty() = server.enqueue(MockResponse().setResponseCode(204))

    private fun assertRequest(method: String, path: String) {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
    }
}
