package com.discord.widgets.servers

import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.google.gson.JsonParser
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class ServerWriteContractTest {
    private val server = MockWebServer()
    private val api by lazy {
        RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java)
    }

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun `channel reorder sends official positions and explicit root parent`() {
        server.enqueue(MockResponse().setResponseCode(204))
        api.reorderChannels(
            10,
            listOf(
                RestAPIParams.ChannelPosition(9_223_372_036_854_775_000, 3, null),
                RestAPIParams.ChannelPosition(
                    9_223_372_036_854_775_001,
                    4,
                    RestAPIParams.ChannelPosition.PARENT_ID_NONE,
                ),
                RestAPIParams.ChannelPosition(9_223_372_036_854_775_002, 5, 9_223_372_036_854_775_003),
            ),
        ).toBlocking().subscribe()

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonArray
        assertEquals("PATCH", request.method)
        assertEquals("/api/v9/guilds/10/channels", request.path)
        assertEquals("9223372036854775000", body[0].asJsonObject["id"].asString)
        assertEquals(3, body[0].asJsonObject["position"].asInt)
        assertFalse(body[0].asJsonObject.has("parent_id"))
        assertTrue(body[1].asJsonObject["parent_id"].isJsonNull)
        assertEquals("9223372036854775003", body[2].asJsonObject["parent_id"].asString)
    }

    @Test
    fun `accepted reorder keeps optimistic state and real failure rolls back`() {
        var saved = 0
        var rollbacks = 0
        var failure: Throwable? = null

        listOf(200, 204).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status))
            api.reorderChannels(10, listOf(RestAPIParams.ChannelPosition(20, 3, null)))
                .subscribe({ _: Void? -> saved++ }, { rollbacks++ })
        }

        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"message\":\"invalid position\"}"))
        api.reorderChannels(10, listOf(RestAPIParams.ChannelPosition(20, 3, null)))
            .subscribe({ _: Void? -> saved++ }, {
                rollbacks++
                failure = it
            })

        assertEquals(2, saved)
        assertEquals(1, rollbacks)
        assertTrue(failure is HttpException)
    }

    @Test
    fun `role toggles send a partial mutable role body`() {
        server.enqueue(MockResponse().setResponseCode(204))
        api.updateRole(10, 20, RestAPIParams.Role(permissions = 8L)).toBlocking().subscribe()

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("PATCH", request.method)
        assertEquals("/api/v9/guilds/10/roles/20", request.path)
        assertEquals("8", body["permissions"].asString)
        assertFalse(body.has("id"))
        assertFalse(body.has("position"))
    }

    @Test
    fun `role create returns the created role payload`() {
        server.enqueue(
            MockResponse().setBody(
                """{"position":2,"name":"new role","id":"20","color":0,"hoist":false,"permissions":"0","mentionable":false,"managed":false,"icon":null,"tags":null,"unicode_emoji":null}""",
            ),
        )

        val role = api.createRole(10).toBlocking().single()

        assertEquals(20L, role.id)
        assertEquals("new role", role.name)
        assertEquals(0L, role.permissions)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v9/guilds/10/roles", request.path)
    }

    @Test
    fun `role create still reports an http failure`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"message\":\"invalid role\"}"))
        var failure: Throwable? = null

        api.createRole(10).subscribe({}, { failure = it })

        assertTrue(failure is HttpException)
    }

    @Test
    fun `role update accepts void success and reports real failure`() {
        var successes = 0
        var failure: Throwable? = null
        server.enqueue(MockResponse().setBody("{\"ignored\":true}"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"message\":\"invalid role\"}"))

        api.updateRole(10, 20, RestAPIParams.Role(name = "Moderator"))
            .subscribe({ _: Void? -> successes++ }, { failure = it })
        api.updateRole(10, 20, RestAPIParams.Role(name = "Moderator"))
            .subscribe({ _: Void? -> successes++ }, { failure = it })

        assertEquals(1, successes)
        assertTrue(failure is HttpException)
    }
}
