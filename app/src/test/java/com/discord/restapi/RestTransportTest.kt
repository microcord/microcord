package com.discord.restapi

import com.discord.restapi.http.RateLimitInterceptor
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.models.domain.ModelGuildFolder
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
import java.util.UUID

class RestTransportTest {
    private val server = MockWebServer()

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun builderAddsVersionHeadersAndPersistsCookies() {
        server.enqueue(MockResponse().setBody("{\"value\":\"first\"}").addHeader("Set-Cookie", "session=abc; Path=/"))
        server.enqueue(MockResponse().setBody("{\"value\":\"second\"}"))
        val headers = object : RequiredHeadersInterceptor.HeadersProvider {
            override fun getAuthToken() = "fixture-token"
            override fun getFingerprint() = "fixture-fingerprint"
            override fun getLocale() = "tr"
            override fun getAcceptLanguages() = "tr-TR,tr;q=0.9"
            override fun getUserAgent() = "Manticord fixture"
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
        assertEquals("Manticord fixture", first.getHeader("User-Agent"))
        assertEquals("session=abc", second.getHeader("Cookie"))
    }

    @Test
    fun analyticsInterceptorAddsSuperProperties() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(RestAPI.buildAnalyticsInterceptor { "fixture-super-properties" })
            .build()

        client.newCall(request("/metadata")).execute().close()

        assertEquals("fixture-super-properties", server.takeRequest().getHeader("X-Super-Properties"))
    }

    @Test
    fun reactNativeInterceptorIsScopedAndAddsStickerSendMetadata() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(ReactNativeHeadersInterceptor)
            .build()

        client.newCall(request("/rn-sticker")).execute().close()

        val sent = server.takeRequest()
        assertEquals(ReactNativeRequestMetadata.USER_AGENT, sent.getHeader("User-Agent"))
        assertFalse(sent.getHeader("X-Super-Properties").isNullOrBlank())
        assertFalse(sent.getHeader("X-Discord-Timezone").isNullOrBlank())
    }

    @Test
    fun offlineSuperPropertiesCanUseAGeneratedAdvertiserId() {
        val fakeAdvertiserId = UUID.randomUUID().toString()
        AnalyticSuperProperties.setAdvertiserId(fakeAdvertiserId)
        val encoded = Base64.getEncoder().encodeToString(
            AnalyticSuperProperties.superPropertiesString.toByteArray(),
        )
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(RestAPI.buildAnalyticsInterceptor { encoded })
            .build()

        client.newCall(request("/offline-metadata")).execute().close()

        val decoded = String(Base64.getDecoder().decode(
            requireNotNull(server.takeRequest().getHeader("X-Super-Properties")),
        ))
        assertTrue(decoded.contains("\"device_advertiser_id\":\"$fakeAdvertiserId\""))
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
