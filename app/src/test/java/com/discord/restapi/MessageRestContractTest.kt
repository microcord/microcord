package com.discord.restapi

import com.discord.api.message.activity.MessageActivityType
import com.discord.restapi.utils.CountingRequestBody
import com.discord.utilities.rest.EMPTY_CONTEXT_PROPERTIES
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
            null,
            EMPTY_CONTEXT_PROPERTIES,
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
        server.enqueue(MockResponse().setBody("""{"items":[],"has_more":false}"""))
        api.getChannelPins(42).toBlocking().single()
        assertRequest("GET", "/api/v9/channels/42/messages/pins?limit=25")

        enqueueEmpty()
        api.addChannelPin(42, 8).toBlocking().subscribe()
        assertRequest("PUT", "/api/v9/channels/42/messages/pins/8")

        enqueueEmpty()
        api.deleteChannelPin(42, 8).toBlocking().subscribe()
        assertRequest("DELETE", "/api/v9/channels/42/messages/pins/8")

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
