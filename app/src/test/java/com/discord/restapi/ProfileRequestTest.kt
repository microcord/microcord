package com.discord.restapi

import com.discord.api.user.PatchUserProfileBody
import com.discord.nullserializable.NullSerializable
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The profile endpoints encode "clear this" two different ways depending on the field, so this
 * pins the bytes rather than the body class.
 */
class ProfileRequestTest {
    private val server = MockWebServer()
    private val api by lazy {
        RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java)
    }

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun `an accent colour goes out as an integer and untouched fields are omitted`() {
        server.enqueue(MockResponse().setBody("""{"pronouns":"they/them"}"""))

        api.patchUserProfile(
            PatchUserProfileBody(
                pronouns = "they/them",
                accentColor = NullSerializable.Value(0x5865F2),
            ),
        ).subscribe({ }, { })

        val sent = server.takeRequest()
        assertEquals("PATCH", sent.method)
        assertEquals("/api/v9/users/@me/profile", sent.path)
        assertEquals("""{"pronouns":"they/them","accent_color":5793266}""", sent.body.readUtf8())
    }

    @Test
    fun `clearing sends an empty string for pronouns and an explicit null for the banner`() {
        server.enqueue(MockResponse().setBody("""{"pronouns":""}"""))

        api.patchUserProfile(
            PatchUserProfileBody(
                banner = NullSerializable.Null(),
                bio = "",
                pronouns = "",
            ),
        ).subscribe({ }, { })

        assertEquals(
            """{"banner":null,"bio":"","pronouns":""}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `a guild profile patch targets the guild route`() {
        server.enqueue(MockResponse().setBody("""{"guild_id":"7","pronouns":"any/all"}"""))

        api.patchGuildProfile(7L, PatchUserProfileBody(pronouns = "any/all")).subscribe({ }, { })

        val sent = server.takeRequest()
        assertEquals("/api/v9/guilds/7/profile/@me", sent.path)
        assertEquals("""{"pronouns":"any/all"}""", sent.body.readUtf8())
    }

    @Test
    fun `the guild id in the response distinguishes a guild profile from the global one`() {
        server.enqueue(MockResponse().setBody("""{"guild_id":"7","pronouns":"any/all","bio":"hi"}"""))

        val metadata = api.patchGuildProfile(7L, PatchUserProfileBody(pronouns = "any/all"))
            .toBlocking()
            .first()

        assertEquals(7L, metadata.guildId)
        assertEquals("any/all", metadata.pronouns)
        assertEquals("hi", metadata.bio)
    }
}
