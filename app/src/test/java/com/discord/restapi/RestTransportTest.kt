package com.discord.restapi

import com.discord.restapi.http.RateLimitInterceptor
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.models.domain.ModelGuildFolder
import com.discord.api.guild.GuildOnboardingResponses
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.GET
import rx.Observable
import java.util.Base64

class RestTransportTest {
    private val server = MockWebServer()

    @After
    fun stopServer() {
        RestAPI.reset()
        server.shutdown()
    }

    private fun installDefaultApi() {
        val headers = object : RequiredHeadersInterceptor.HeadersProvider {
            override fun getAuthToken() = "fixture-token"
            override fun getFingerprint() = "fixture-fingerprint"
            override fun getLocale() = "en-US"
            override fun getAcceptLanguages() = "en-US"
            override fun getSpotifyToken(): String? = null
        }
        RestAPI.install(
            RestAPIBuilder(server.url("/api/").toString(), MemoryCookieJar()),
            listOf(RequiredHeadersInterceptor(headers)),
        )
    }

    @Test
    fun builderAddsVersionHeadersAndPersistsCookies() {
        server.enqueue(MockResponse().setBody("{\"value\":\"first\"}").addHeader("Set-Cookie", "session=abc; Path=/"))
        server.enqueue(MockResponse().setBody("{\"value\":\"second\"}"))
        val headers = object : RequiredHeadersInterceptor.HeadersProvider {
            override fun getAuthToken() = "fixture-token"
            override fun getFingerprint() = "fixture-fingerprint"
            override fun getLocale() = "tr"
            override fun getAcceptLanguages() = "tr-TR,tr;q=0.9"
            override fun getSpotifyToken(): String? = null
        }
        val api = RestAPIBuilder(server.url("/api/").toString(), MemoryCookieJar()).build(
            FixtureApi::class.java,
            interceptors = listOf(RequiredHeadersInterceptor(headers)),
        )

        assertEquals("first", api.get().toBlocking().single().value)
        assertEquals("second", api.get().toBlocking().single().value)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/api/v9/fixture", first.path)
        assertEquals("fixture-token", first.getHeader("Authorization"))
        assertEquals("fixture-fingerprint", first.getHeader("X-Fingerprint"))
        assertEquals("tr", first.getHeader("X-Discord-Locale"))
        assertEquals("tr-TR,tr;q=0.9", first.getHeader("Accept-Language"))
        assertEquals("session=abc", second.getHeader("Cookie"))
    }

    @Test
    fun embeddedActivityTokenClientHasNoUserTokenOrCookies() {
        server.enqueue(
            MockResponse().setBody(
                """{"application":{"id":"12","name":"Activity"},"scopes":["identify"],"expires":"2026-08-04T00:00:00Z","user":{"id":"7","username":"user","discriminator":"0007"}}""",
            ),
        )
        val headers = object : RequiredHeadersInterceptor.HeadersProvider {
            override fun getAuthToken() = "user-token"
            override fun getFingerprint(): String? = null
            override fun getLocale(): String? = null
            override fun getAcceptLanguages(): String? = null
            override fun getSpotifyToken(): String? = null
        }
        val cookieJar = MemoryCookieJar()
        cookieJar.saveFromResponse(
            server.url("/api/"),
            listOf(
                Cookie.Builder()
                    .name("session")
                    .value("user-cookie")
                    .hostOnlyDomain(server.url("/").host)
                    .path("/")
                    .build(),
            ),
        )
        RestAPI.install(
            RestAPIBuilder(server.url("/api/").toString(), cookieJar),
            listOf(RequiredHeadersInterceptor(headers)),
        )

        RestAPI.embeddedActivityOAuthTokenApiOrNull!!
            .authenticate("Bearer activity-token")
            .toBlocking()
            .single()

        server.takeRequest().also { request ->
            assertEquals("Bearer activity-token", request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("Cookie"))
            assertEquals(AnalyticSuperProperties.USER_AGENT, request.getHeader("User-Agent"))
        }
    }

    @Test
    fun everyClientCarriesTheSameClientMetadata() {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("{\"activities\":[],\"applications\":[],\"assets\":{}}"))
        server.enqueue(MockResponse().setBody("{\"applications\":[],\"application_commands\":[]}"))
        server.enqueue(MockResponse().setBody("{\"applications\":[],\"application_commands\":[]}"))
        server.enqueue(MockResponse().setBody("{\"threads\":[],\"members\":[],\"has_more\":false}"))
        server.enqueue(MockResponse().setResponseCode(204))
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), MemoryCookieJar()), emptyList())

        RestAPI.applicationDirectoryApiOrNull!!.categories("en-US").toBlocking().single()
        RestAPI.embeddedActivitiesApiOrNull!!.shelf().toBlocking().single()
        RestAPI.api.getUserApplicationCommandIndex().toBlocking().single()
        RestAPI.applicationDirectoryApiOrNull!!.applicationCommandIndex(10L).toBlocking().single()
        RestAPI.api.searchForumThreads(20L).toBlocking().single()
        RestAPI.api.sendApplicationCommand(
            RestAPIParams.ApplicationCommand(
                type = 4L,
                channelId = "7",
                applicationId = "10",
                guildId = "20",
                data = com.discord.api.commands.ApplicationCommandData(
                    version = "2",
                    guildId = "20",
                    id = "30",
                    name = "search",
                    options = listOf(
                        com.discord.api.commands.ApplicationCommandValue(
                            name = "query",
                            value = "fixture",
                            type = 3,
                            options = null,
                            focused = true,
                        ),
                    ),
                    attachments = null,
                ),
                sessionId = "session-fixture",
                nonce = "nonce-fixture",
            ),
        ).toBlocking().subscribe()

        val requests = List(6) {
            val request = server.takeRequest()
            assertEquals(AnalyticSuperProperties.USER_AGENT, request.getHeader("User-Agent"))
            assertFalse(request.getHeader("X-Super-Properties").isNullOrBlank())
            assertFalse(request.getHeader("X-Discord-Timezone").isNullOrBlank())
            request
        }
        assertEquals("/api/v9/applications/10/application-command-index", requests[3].path)
        assertEquals("/api/v9/channels/20/threads/search", requests[4].path)
        assertEquals("/api/v9/interactions", requests.last().path)
        assertTrue(requests.last().body.readUtf8().contains("\"type\":4"))
    }

    @Test
    fun guildOnboardingUsesReactNativeTransportHeaders() {
        val response = """{"guild_id":"1","prompts":[],"default_channel_ids":[],"responses":[],"mode":0,"enabled":true,"onboarding_prompts_seen":{},"onboarding_responses_seen":{},"below_requirements":false,"connections":[]}"""
        server.enqueue(MockResponse().setBody(response))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        installDefaultApi()

        RestAPI.api.getGuildOnboarding(1).toBlocking().single()
        RestAPI.api.completeGuildOnboarding(
            1,
            GuildOnboardingResponses(listOf(10), mapOf("2" to 3), mapOf("10" to 3)),
        ).toBlocking().single()
        RestAPI.api.updateGuildOnboardingResponses(
            1,
            GuildOnboardingResponses(listOf(11), mapOf("2" to 4), mapOf("11" to 4)),
        ).toBlocking().single()

        val requests = listOf(server.takeRequest(), server.takeRequest(), server.takeRequest())
        requests.forEach { request ->
            assertEquals("fixture-token", request.getHeader("Authorization"))
            assertEquals(AnalyticSuperProperties.USER_AGENT, request.getHeader("User-Agent"))
            assertFalse(request.getHeader("X-Super-Properties").isNullOrBlank())
            assertFalse(request.getHeader("X-Discord-Timezone").isNullOrBlank())
        }
        assertEquals("GET", requests[0].method)
        assertEquals("/api/v9/guilds/1/onboarding", requests[0].path)
        assertEquals("POST", requests[1].method)
        assertEquals("/api/v9/guilds/1/onboarding-responses", requests[1].path)
        assertTrue(requests[1].body.readUtf8().contains("\"onboarding_responses\":[10]"))
        assertEquals("PUT", requests[2].method)
        assertEquals("/api/v9/guilds/1/onboarding-responses", requests[2].path)
        assertTrue(requests[2].body.readUtf8().contains("\"onboarding_responses\":[11]"))
        run {
            val get = RestAPIInterface::class.java.getMethod("getGuildOnboarding", Long::class.javaPrimitiveType)
            val post = RestAPIInterface::class.java.getMethod(
                "completeGuildOnboarding",
                Long::class.javaPrimitiveType,
                GuildOnboardingResponses::class.java,
            )
            val put = RestAPIInterface::class.java.getMethod(
                "updateGuildOnboardingResponses",
                Long::class.javaPrimitiveType,
                GuildOnboardingResponses::class.java,
            )
            assertEquals("guilds/{guildId}/onboarding", get.getAnnotation(retrofit2.http.GET::class.java).value)
            assertEquals("guilds/{guildId}/onboarding-responses", post.getAnnotation(retrofit2.http.POST::class.java).value)
            assertEquals("guilds/{guildId}/onboarding-responses", put.getAnnotation(retrofit2.http.PUT::class.java).value)
        }
    }

    @Test
    fun guildTagProfileUsesReactNativeTransportHeaders() {
        server.enqueue(MockResponse().setBody(
            """{"id":"1","name":"Guild","icon_hash":"icon","member_count":10,"online_count":2,"description":"Profile","brand_color_primary":"#000000","game_application_ids":[],"game_activity":{},"tag":"TAG","badge":1,"badge_color_primary":"#000000","badge_color_secondary":"#000000","badge_hash":"badge","traits":[],"features":[],"visibility":1,"custom_banner_hash":null,"premium_subscription_count":0,"premium_tier":0}""",
        ))
        installDefaultApi()

        RestAPI.api.getGuildProfile(1).toBlocking().single()
        val request = server.takeRequest()
        assertEquals("/api/v9/guilds/1/profile", request.path)
        assertEquals("fixture-token", request.getHeader("Authorization"))
        assertFalse(request.getHeader("X-Super-Properties").isNullOrBlank())
        assertFalse(request.getHeader("X-Discord-Timezone").isNullOrBlank())
    }

    @Test
    fun profileApplicationIdentitiesUseDefaultTransportHeaders() {
        server.enqueue(MockResponse().setBody("""{"identities":[]}"""))
        installDefaultApi()

        RestAPI.api.userApplicationIdentitiesGet(2).toBlocking().single()
        val request = server.takeRequest()
        assertEquals("/api/v9/users/2/application-identities?with_profiles=true", request.path)
        assertEquals("fixture-token", request.getHeader("Authorization"))
        assertFalse(request.getHeader("X-Super-Properties").isNullOrBlank())
        assertFalse(request.getHeader("X-Discord-Timezone").isNullOrBlank())
    }

    @Test
    fun gameMetadataUsesDefaultTransportHeaders() {
        server.enqueue(MockResponse().setBody("[]"))
        installDefaultApi()

        RestAPI.api.getGames(listOf(1L, 2L)).toBlocking().single()
        val request = server.takeRequest()
        assertEquals("/api/v9/games?game_ids=1&game_ids=2", request.path)
        assertEquals("fixture-token", request.getHeader("Authorization"))
        assertFalse(request.getHeader("X-Super-Properties").isNullOrBlank())
        assertFalse(request.getHeader("X-Discord-Timezone").isNullOrBlank())
    }

    @Test
    fun superPropertiesRideAlongAsBase64() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder().addInterceptor(ClientMetadataInterceptor).build()

        client.newCall(request("/offline-metadata")).execute().close()

        val decoded = String(Base64.getDecoder().decode(
            requireNotNull(server.takeRequest().getHeader("X-Super-Properties")),
        ))
        assertTrue(decoded.contains("\"browser\":\"Discord Android\""))
        assertTrue(decoded.contains("\"client_event_source\":null"))
    }

    @Test
    fun guildFolderPatchContainsOnlyGuildFolders() {
        server.enqueue(MockResponse().setBody("{}"))
        val api = RestAPIBuilder(server.url("/api/").toString(), MemoryCookieJar()).build(RestAPIInterface::class.java)

        api.updateUserSettings(RestAPIParams.GuildFoldersUpdate(listOf(ModelGuildFolder(null, listOf(1))))).toBlocking().first()

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v9/users/@me/settings", request.path)
        assertEquals("{\"guild_folders\":[{\"guild_ids\":[1]}]}", request.body.readUtf8())
    }

    @Test
    fun parsesStructuredErrorsAndRedactsSecrets() {
        val request = Request.Builder().url("https://example.test/channels?token=query-secret").build()
        val raw = okhttp3.Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .message("Bad Request")
            .code(400)
            .build()
        val exception = HttpException(Response.error<Any>(fixture("structured_error.json").toResponseBody(), raw))
        val error = RestApiError.from(exception)

        assertEquals(RestApiError.Type.DISCORD_BAD_REQUEST, error.type)
        assertEquals(50035, error.code)
        assertEquals(listOf("This field is required"), error.messages["name"])
        assertFalse(error.toString().contains("query-secret"))
        val secret = RestApiError.from(java.io.IOException("Authorization: Bearer fixture-secret"))
        assertFalse(secret.toString().contains("fixture-secret"))
        assertTrue(secret.toString().contains("<redacted>"))
    }

    @Test
    fun coordinatesGlobalAndRouteLimits() {
        val clock = MutableClock()
        val client = OkHttpClient.Builder().addInterceptor(
            RateLimitInterceptor(maxRetries = 0, nowMillis = clock::now, sleep = clock::sleep),
        ).build()
        server.enqueue(MockResponse().setResponseCode(429).setBody(fixture("global_rate_limit.json")))
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals(429, client.newCall(request("/first")).execute().use { it.code })
        assertEquals(200, client.newCall(request("/different")).execute().use { it.code })
        assertEquals(listOf(500L), clock.sleeps)

        val routeClock = MutableClock()
        val routeClient = OkHttpClient.Builder().addInterceptor(
            RateLimitInterceptor(maxRetries = 0, nowMillis = routeClock::now, sleep = routeClock::sleep),
        ).build()
        server.enqueue(MockResponse().setResponseCode(429).setBody(fixture("route_rate_limit.json")))
        server.enqueue(MockResponse().setBody("other"))
        server.enqueue(MockResponse().setBody("same"))
        routeClient.newCall(request("/route")).execute().close()
        routeClient.newCall(request("/other")).execute().close()
        routeClient.newCall(request("/route")).execute().close()
        assertEquals(listOf(250L), routeClock.sleeps)
    }

    @Test
    fun rateLimitInterceptorDoesNotReplayRequests() {
        val clock = MutableClock()
        server.enqueue(MockResponse().setResponseCode(429).setBody(fixture("route_rate_limit.json")))
        val client = OkHttpClient.Builder().addInterceptor(
            RateLimitInterceptor(nowMillis = clock::now, sleep = clock::sleep),
        ).build()

        assertEquals(429, client.newCall(request("/bounded")).execute().use { it.code })
        assertEquals(1, server.requestCount)
        assertTrue(clock.sleeps.isEmpty())
    }

    private fun request(path: String) = Request.Builder().url(server.url(path)).build()

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/rest/$name"),
    ).readText()

    private interface FixtureApi {
        @GET("fixture")
        fun get(): Observable<FixtureResponse>
    }

    private data class FixtureResponse(val value: String)

    private class MutableClock {
        var value = 0L
        val sleeps = mutableListOf<Long>()
        fun now() = value
        fun sleep(delay: Long) {
            sleeps += delay
            value += delay
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.removeAll { old ->
                cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path }
            }
            this.cookies += cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }
}
