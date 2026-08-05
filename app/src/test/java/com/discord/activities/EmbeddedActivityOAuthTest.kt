package com.discord.activities

import com.discord.api.channel.Channel
import com.discord.restapi.EmbeddedActivityOAuthAPI
import com.discord.restapi.ClientMetadataInterceptor
import com.discord.restapi.RestAPIBuilder
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedActivityOAuthTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

    @Test
    fun authorizeUsesRnHeadersTypedLocationAndExtractsMatchingCode() {
        withServer { server, api ->
            server.enqueue(MockResponse().setBody("{\"location\":\"https://activity.example/callback?code=oauth-code&state=state-1\"}"))
            val challenge = "a".repeat(43)
            val code = client(api).authorize(
                EmbeddedActivityAuthorizeRequest(
                    12,
                    listOf("identify", "guilds"),
                    "state-1",
                    challenge,
                    "S256",
                ),
            ).toBlocking().single()

            assertEquals("oauth-code", code)
            server.takeRequest().also { request ->
                assertEquals(
                    "/api/v9/oauth2/authorize?client_id=12&response_type=code&scope=identify%20guilds&state=state-1&code_challenge=$challenge&code_challenge_method=S256",
                    request.path,
                )
                assertNotNull(request.getHeader("X-Super-Properties"))
                assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("Discord-Android/"))
                val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                assertEquals(1, body["integration_type"].asInt)
                assertTrue(body["authorize"].asBoolean)
                body["location_context"].asJsonObject.also { location ->
                    assertEquals("2", location["channel_id"].asString)
                    assertEquals(0, location["channel_type"].asInt)
                    assertEquals("1", location["guild_id"].asString)
                }
            }
        }
    }

    @Test
    fun authenticateUsesOnlyProvidedBearerAndRedactsIt() {
        withServer { server, api ->
            server.enqueue(MockResponse().setBody(AUTH_RESPONSE))
            val result = client(api).authenticate("activity-access-token").toBlocking().single()

            assertEquals("activity-access-token", result.accessToken)
            assertEquals("Activity description", result.authorization.application.description)
            assertEquals(64, result.authorization.user?.publicFlags)
            assertFalse(result.toString().contains("activity-access-token"))
            server.takeRequest().also { request ->
                assertEquals("/api/v9/oauth2/@me", request.path)
                assertEquals("Bearer activity-access-token", request.getHeader("Authorization"))
                assertNotNull(request.getHeader("X-Super-Properties"))
            }
        }
    }

    @Test
    fun rejectsInvalidClientScopeStateAndReturnedState() {
        withServer { server, api ->
            val client = client(api)
            assertTrue(
                client.authorize(EmbeddedActivityAuthorizeRequest(99, listOf("identify"), null))
                    .materialize().toBlocking().single().throwable is EmbeddedActivityOAuthException.InvalidClient,
            )
            assertTrue(
                client.authorize(EmbeddedActivityAuthorizeRequest(12, listOf("identify bad"), null))
                    .materialize().toBlocking().single().throwable is EmbeddedActivityOAuthException.InvalidRequest,
            )
            assertTrue(
                client.authorize(EmbeddedActivityAuthorizeRequest(12, listOf("identify"), "bad\u0000state"))
                    .materialize().toBlocking().single().throwable is EmbeddedActivityOAuthException.InvalidRequest,
            )
            assertTrue(
                client.authorize(EmbeddedActivityAuthorizeRequest(12, listOf("identify"), null, "short", "S256"))
                    .materialize().toBlocking().single().throwable is EmbeddedActivityOAuthException.InvalidRequest,
            )
            assertEquals(0, server.requestCount)

            server.enqueue(MockResponse().setBody("{\"location\":\"https://activity.example/?code=code&state=wrong\"}"))
            assertTrue(
                client.authorize(EmbeddedActivityAuthorizeRequest(12, listOf("identify"), "expected"))
                    .materialize().toBlocking().single().throwable is EmbeddedActivityOAuthException.AuthorizationFailed,
            )
        }
    }

    @Test
    fun destroyedViewSuppressesQueuedOAuthResult() {
        assertTrue(EmbeddedActivityWebView.shouldDeliverAsyncResult(false))
        assertFalse(EmbeddedActivityWebView.shouldDeliverAsyncResult(true))
    }

    private fun client(api: EmbeddedActivityOAuthAPI) =
        EmbeddedActivityOAuthClient(api, api, 12, channel(), 7)

    private fun channel(): Channel = gson.fromJson(
        """{"id":2,"guild_id":1,"type":0,"name":"Text"}""",
        Channel::class.java,
    )

    private fun withServer(block: (MockWebServer, EmbeddedActivityOAuthAPI) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            val api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES).build(
                EmbeddedActivityOAuthAPI::class.java,
                interceptors = listOf(ClientMetadataInterceptor),
            )
            block(server, api)
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val AUTH_RESPONSE = """
            {"application":{"id":"12","name":"Activity","description":"Activity description","icon":null},"scopes":["identify"],"expires":"2026-08-04T00:00:00Z","user":{"id":"7","username":"user","discriminator":"0007","avatar":null,"global_name":"User","public_flags":64}}
        """
    }
}
