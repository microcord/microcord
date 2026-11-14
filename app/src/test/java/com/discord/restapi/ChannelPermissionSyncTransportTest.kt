package com.discord.restapi

import com.discord.api.permission.PermissionOverwrite
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.rest.RestAPI
import com.google.gson.JsonParser
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelPermissionSyncTransportTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        RestAPI.reset()
        server.shutdown()
    }

    @Test
    fun syncSendsTheCategoryOverwritesWithDefaultMetadata() {
        server.enqueue(MockResponse().setBody("""{"id":"20","type":0,"guild_id":"1"}"""))
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), EmptyCookieJar), emptyList())

        RestAPI.api.syncChannelPermissions(
            20,
            RestAPIParams.ChannelPermissionSync(
                listOf(
                    RestAPIParams.ChannelPermissionOverwrites.fromPermissionOverwrite(
                        PermissionOverwrite(1, PermissionOverwrite.Type.ROLE, 0, 1024),
                    ),
                ),
            ),
        ).toBlocking().single()

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(AnalyticSuperProperties.USER_AGENT, request.getHeader("User-Agent"))
        assertEquals(AnalyticSuperProperties.superPropertiesStringBase64, request.getHeader("X-Super-Properties"))
        assertEquals(TimeZone.getDefault().id, request.getHeader("X-Discord-Timezone"))
        assertEquals("PATCH", request.method)
        assertEquals("/api/v9/channels/20", request.path)
        val overwrite = JsonParser.parseString(request.body.readUtf8()).asJsonObject["permission_overwrites"]
            .asJsonArray.single().asJsonObject
        assertEquals("1", overwrite["id"].asString)
        assertEquals(0, overwrite["type"].asInt)
        assertEquals(0L, overwrite["allow"].asLong)
        assertEquals(1024L, overwrite["deny"].asLong)
    }

    private object EmptyCookieJar : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }
}
