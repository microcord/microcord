package com.discord.restapi

import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationCommandRestContractTest {
    private val server = MockWebServer()
    private val api by lazy {
        RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java)
    }

    @After fun stopServer() = server.shutdown()

    @Test
    fun modernCommandIndexesUseLocationSpecificEndpoints() {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(INDEX))
        }

        api.getGuildApplicationCommandIndex(42L).toBlocking().single()
        api.getChannelApplicationCommandIndex(43L).toBlocking().single()
        api.getUserApplicationCommandIndex().toBlocking().single()

        assertEquals("/api/v9/guilds/42/application-command-index", server.takeRequest().path)
        assertEquals("/api/v9/channels/43/application-command-index", server.takeRequest().path)
        assertEquals("/api/v9/users/@me/application-command-index", server.takeRequest().path)
    }

    private companion object {
        const val INDEX = """{"applications":[],"application_commands":[],"version":"1"}"""
    }
}
