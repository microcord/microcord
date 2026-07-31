package com.discord.widgets.servers

import com.discord.api.role.GuildRole
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
    fun `role permission update sends the cloned role as numeric fields and accepts 204 completion`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val values = mutableListOf<Void?>()
        var completed = false
        var failure: Throwable? = null

        val update = RestAPIParams.Role.createWithRole(role()).apply { permissions = 8L }
        api.updateRole(10, 20, update).subscribe(
            { values.add(it) },
            { failure = it },
            { completed = true },
        )

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("PATCH", request.method)
        assertEquals("/api/v9/guilds/10/roles/20", request.path)
        assertTrue(body["permissions"].asJsonPrimitive.isNumber)
        assertEquals(8L, body["permissions"].asLong)
        assertTrue(body["id"].asJsonPrimitive.isNumber)
        assertEquals(20L, body["id"].asLong)
        assertTrue(body["color"].asJsonPrimitive.isNumber)
        assertEquals(0, body["color"].asInt)
        assertTrue(body["position"].asJsonPrimitive.isNumber)
        assertEquals(2, body["position"].asInt)
        assertEquals(listOf<Void?>(null), values)
        assertTrue(completed)
        assertEquals(null, failure)
    }

    @Test
    fun `role create returns the created role payload`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
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
    fun `role name update ignores a 200 role body and completes`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"position":2,"name":"Moderator","id":"20","color":0,"hoist":false,"permissions":"8","mentionable":false,"managed":false,"icon":null,"tags":null,"unicode_emoji":null}""",
            ),
        )
        var values = 0
        var completed = false
        var failure: Throwable? = null

        api.updateRole(10, 20, RestAPIParams.Role.createWithRole(role()).apply { name = "Moderator" })
            .subscribe({ values++ }, { failure = it }, { completed = true })

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("Moderator", body["name"].asString)
        assertEquals(1, values)
        assertTrue(completed)
        assertEquals(null, failure)
    }

    @Test
    fun `role color update changes color while preserving the cloned role`() {
        server.enqueue(MockResponse().setResponseCode(204))

        api.updateRole(10, 20, RestAPIParams.Role.createWithRole(role()).apply { color = 0x12AB34 })
            .toBlocking().subscribe()

        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals(0x12AB34, body["color"].asInt)
        assertEquals("member", body["name"].asString)
        assertEquals(20L, body["id"].asLong)
    }

    @Test
    fun `role delete accepts 204 completion and real failures still error`() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"missing permissions\"}"))
        var completed = false
        var failure: Throwable? = null

        api.deleteRole(10, 20).subscribe({}, { failure = it }, { completed = true })
        val deleted = server.takeRequest()
        assertEquals("DELETE", deleted.method)
        assertEquals("/api/v9/guilds/10/roles/20", deleted.path)

        api.deleteRole(10, 20).subscribe({}, { failure = it })

        assertTrue(completed)
        assertTrue(failure is HttpException)
    }

    @Test
    fun `role update still reports an http failure`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"message\":\"invalid role\"}"))
        var completed = false
        var failure: Throwable? = null

        api.updateRole(10, 20, RestAPIParams.Role(name = "Moderator"))
            .subscribe({}, { failure = it }, { completed = true })

        assertFalse(completed)
        assertTrue(failure is HttpException)
    }

    private fun role() = GuildRole(
        position = 2,
        name = "member",
        id = 20,
        color = 0,
        hoist = false,
        permissions = 0,
        mentionable = false,
        managed = false,
        icon = null,
        tags = null,
        unicodeEmoji = null,
    )
}
