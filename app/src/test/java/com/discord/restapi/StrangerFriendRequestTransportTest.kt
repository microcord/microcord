package com.discord.restapi

import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.contextPropertiesOf
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class StrangerFriendRequestTransportTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        RestAPI.reset()
        server.shutdown()
    }

    @Test
    fun confirmationIsSentOnlyOnTheConfirmedRetry() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), EmptyCookieJar), emptyList())
        val context = contextPropertiesOf("location" to "Friends")

        RestAPI.api.addRelationship(7, RestAPIParams.UserRelationship(), context).toBlocking().subscribe()
        RestAPI.api.addRelationship(7, RestAPIParams.UserRelationship(confirmStrangerRequest = true), context)
            .toBlocking().subscribe()

        val first = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val retry = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        listOf(first, retry).forEach {
            assertEquals("PUT", it.method)
            assertEquals("/api/v9/users/@me/relationships/7", it.path)
            assertEquals(AnalyticSuperProperties.superPropertiesStringBase64, it.getHeader("X-Super-Properties"))
        }
        assertEquals("{}", first.body.readUtf8())
        assertEquals("""{"confirm_stranger_request":true}""", retry.body.readUtf8())
    }

    private object EmptyCookieJar : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }
}
