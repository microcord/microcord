package com.discord.restapi

import com.discord.nullserializable.NullSerializable
import com.discord.utilities.rx.appSubscribe
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 204 must reach the success callback. The nickname dialog reported a failure toast for a request
 * the server had accepted, so this pins the whole round trip rather than the wire format.
 */
class FriendNicknameRequestTest {
    private val server = MockWebServer()
    private val api by lazy {
        RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java)
    }

    @After
    fun stopServer() = server.shutdown()

    /** Every `Observable<Unit>` endpoint emits Unit for a 204, so Kotlin callbacks never see null. */
    @Test
    fun `a 204 on a Unit endpoint emits Unit`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val values = mutableListOf<Unit>()
        var failure: Throwable? = null

        api.logoutAuthSessions(RestAPIParams.LogoutAuthSessions(listOf("hash")))
            .subscribe({ value: Unit -> values += value }, { failure = it })

        assertNull(failure)
        assertEquals(listOf(Unit), values)
        assertEquals("/api/v9/auth/sessions/logout", server.takeRequest().path)
    }

    @Test
    fun `a 204 completes without an error`() {
        server.enqueue(MockResponse().setResponseCode(204))
        var completed = false
        var emissions = 0
        var failure: Throwable? = null

        api.updateRelationshipNickname(7L, RestAPIParams.FriendNickname(NullSerializable.Value("Countess")))
            .subscribe({ emissions++ }, { failure = it }, { completed = true })

        assertNull(failure)
        assertTrue(completed)
        assertEquals(1, emissions)

        val sent = server.takeRequest()
        assertEquals("PATCH", sent.method)
        assertEquals("/api/v9/users/@me/relationships/7", sent.path)
        assertEquals("""{"nickname":"Countess"}""", sent.body.readUtf8())
    }

    /**
     * The 204 emits null, and the generic `appSubscribe` inferred a non-null `Void` for the
     * callback parameter, so the compiler's null check threw before the body ran and the error
     * handler reported a failure for a request the server had accepted.
     */
    @Test
    fun `a 204 reaches the success callback and not the error handler`() {
        server.enqueue(MockResponse().setResponseCode(204))
        var succeeded = false
        var reportedError = false

        api.updateRelationshipNickname(7L, RestAPIParams.FriendNickname(NullSerializable.Value("Countess")))
            .appSubscribe(
                errorClass = FriendNicknameRequestTest::class.java,
                errorHandler = { reportedError = true },
            ) { succeeded = true }

        assertTrue(succeeded)
        assertFalse(reportedError)
    }

    @Test
    fun `clearing the nickname sends an explicit null rather than an empty body`() {
        server.enqueue(MockResponse().setResponseCode(204))

        api.updateRelationshipNickname(7L, RestAPIParams.FriendNickname(NullSerializable.Null()))
            .subscribe({ }, { }, { })

        assertEquals("""{"nickname":null}""", server.takeRequest().body.readUtf8())
    }
}
