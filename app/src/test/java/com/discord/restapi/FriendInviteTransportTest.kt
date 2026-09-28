package com.discord.restapi

import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.contextPropertiesOf
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendInviteTransportTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        RestAPI.reset()
        server.shutdown()
    }

    @Test
    fun listAndCreateUseDefaultMetadataAndParseFriendInvites() {
        server.enqueue(MockResponse().setBody("[$FRIEND_INVITE]"))
        server.enqueue(MockResponse().setBody(FRIEND_INVITE))
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), EmptyCookieJar), emptyList())

        val listed = RestAPI.api.getFriendInvites().toBlocking().single().single()
        val created = RestAPI.api
            .createFriendInvite(context = contextPropertiesOf("location" to "Add Friend"))
            .toBlocking()
            .single()

        assertTrue(listed.isFriendInvite())
        assertEquals("fixture", created.code)
        assertEquals(7L, created.getInviter()?.id)

        val requests = List(2) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        requests.forEach { request ->
            assertEquals(AnalyticSuperProperties.USER_AGENT, request.getHeader("User-Agent"))
            assertEquals(AnalyticSuperProperties.superPropertiesStringBase64, request.getHeader("X-Super-Properties"))
            assertEquals(TimeZone.getDefault().id, request.getHeader("X-Discord-Timezone"))
            assertEquals("/api/v9/users/@me/invites", request.path)
        }
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
        assertEquals("{}", requests[1].body.readUtf8())
    }

    @Test
    fun guildInvitesAreNotFriendInvites() {
        server.enqueue(MockResponse().setBody("""[{"code":"guild","type":0,"guild":{"id":"1","name":"g"},"inviter":{"id":"7","username":"fixture"}}]"""))
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), EmptyCookieJar), emptyList())

        assertFalse(RestAPI.api.getFriendInvites().toBlocking().single().single().isFriendInvite())
    }

    private object EmptyCookieJar : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    private companion object {
        const val FRIEND_INVITE = """{"code":"fixture","type":2,"max_age":604800,"max_uses":5,"uses":0,
            "inviter":{"id":"7","username":"fixture","discriminator":"0"}}"""
    }
}
