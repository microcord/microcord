package com.discord.restapi

import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SoundboardRestContractTest {
    private val server = MockWebServer()
    private val api by lazy {
        RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java, interceptors = listOf(ClientMetadataInterceptor))
    }

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun `lists and sends sounds with client metadata`() {
        server.enqueue(MockResponse().setBody("[]"))
        api.getDefaultSoundboardSounds().toBlocking().first()
        assertEquals("/api/v9/soundboard-default-sounds", server.takeRequest().path)

        server.enqueue(MockResponse().setBody(""))
        api.sendSoundboardSound(42L, RestAPIParams.SendSoundboardSound(7L, 9L))
            .subscribe({}, { throw it })

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v9/channels/42/send-soundboard-sound", request.path)
        assertEquals("""{"sound_id":7,"source_guild_id":9}""", request.body.readUtf8())
        assertNotNull(request.getHeader("User-Agent"))
        assertNotNull(request.getHeader("X-Super-Properties"))
        assertNotNull(request.getHeader("X-Discord-Timezone"))
    }
}
