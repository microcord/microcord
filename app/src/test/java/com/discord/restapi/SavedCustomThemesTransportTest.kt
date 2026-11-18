package com.discord.restapi

import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.theme.ClientTheme
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedCustomThemesTransportTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        RestAPI.reset()
        server.shutdown()
    }

    @Test
    fun savedThemesCarryDefaultMetadataAndBecomeCustomThemes() {
        server.enqueue(
            MockResponse().setBody(
                """{"custom_themes":[{"colors":["5865f2","eb459e"],"gradient_angle":135,"base_mix":75,"base_theme":2}]}""",
            ),
        )
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), NoCookies), emptyList())

        val saved = RestAPI.api.getSavedCustomThemes().toBlocking().single().customThemes.orEmpty().single()

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(AnalyticSuperProperties.superPropertiesStringBase64, request.getHeader("X-Super-Properties"))
        assertEquals("GET", request.method)
        assertEquals("/api/v9/users/@me/custom-themes", request.path)
        val theme = checkNotNull(ClientTheme.fromApi(saved.colors, saved.gradientAngle, saved.baseMix, saved.baseTheme))
        assertEquals(listOf(0xFF5865F2.toInt(), 0xFFEB459E.toInt()), theme.colors)
        assertEquals("light", theme.base)
        assertEquals(75, theme.baseMix)
    }

    private object NoCookies : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }
}
