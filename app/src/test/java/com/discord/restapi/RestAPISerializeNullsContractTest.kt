package com.discord.restapi

import com.discord.utilities.rest.RestAPI
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class RestAPISerializeNullsContractTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        RestAPIBuilder.clientCallback = { _, _ -> }
        RestAPI.reset()
        server.shutdown()
    }

    @Test
    fun `shared client serializes null without changing normal client`() {
        val clients = mutableListOf<String>()
        RestAPIBuilder.clientCallback = { name, _ -> clients += name }
        RestAPI.install(
            RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES),
            emptyList(),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        val body = RestAPIParams.ConnectedAccountNameOnly(null)
        RestAPI.api.updateConnectionName("contacts", "@me", body).toBlocking().first()
        RestAPI.apiSerializeNulls.updateConnectionName("contacts", "@me", body).toBlocking().first()

        assertEquals("{}", server.takeRequest().body.readUtf8())
        assertEquals("{\"name\":null}", server.takeRequest().body.readUtf8())
        assertEquals(1, clients.count { it == "serializeNulls" })
        assertNotSame(RestAPI.api, RestAPI.apiSerializeNulls)
    }

    @Test
    fun `friend request sends personalized note`() {
        RestAPI.install(
            RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES),
            emptyList(),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        RestAPI.apiSerializeNulls.sendRelationshipRequest(
            RestAPIParams.UserRelationship.Add(
                username = "alien",
                discriminator = null,
                note = "Saw you flying by...",
            ),
            "friend-context",
        ).toBlocking().first()

        val request = server.takeRequest()
        assertEquals("/api/v9/users/@me/relationships", request.path)
        assertEquals(
            """{"username":"alien","discriminator":null,"note":"Saw you flying by..."}""",
            request.body.readUtf8(),
        )
        assertEquals("friend-context", request.getHeader("X-Context-Properties"))
    }
}
