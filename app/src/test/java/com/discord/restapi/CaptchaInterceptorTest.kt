package com.discord.restapi

import com.discord.utilities.captcha.CaptchaCancellationException
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaRequestPolicy
import com.discord.utilities.captcha.CaptchaService
import com.discord.utilities.captcha.CaptchaSolution
import com.discord.utilities.captcha.CaptchaSolver
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaInterceptorTest {
    private val server = MockWebServer()

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun `a challenged request is retried with the solution as headers`() {
        server.enqueue(challenge())
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val solver = RecordingSolver { CaptchaSolution.of(it, "solved-token") }

        val response = client(solver).newCall(post()).execute()

        assertEquals(200, response.code)
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("solved-token", retry.getHeader("X-Captcha-Key"))
        assertEquals("test-rqtoken", retry.getHeader("X-Captcha-Rqtoken"))
        assertEquals("test-session", retry.getHeader("X-Captcha-Session-Id"))
        // The retry is the same request, body included.
        assertEquals("""{"content":"hi"}""", retry.body.readUtf8())
    }

    @Test
    fun `the challenge handed to the solver carries every field the provider needs`() {
        server.enqueue(challenge())
        server.enqueue(MockResponse().setBody("{}"))
        val solver = RecordingSolver { CaptchaSolution.of(it, "solved-token") }

        client(solver).newCall(post()).execute()

        val challenge = solver.challenges.single()
        assertEquals("test-site-key", challenge.captchaSitekey)
        assertEquals(CaptchaService.HCAPTCHA, challenge.captchaService)
        assertEquals("enterprise-rqdata", challenge.captchaRqdata)
        assertTrue(challenge.shouldServeInvisible)
        assertEquals("register", challenge.userFlow)
    }

    @Test
    fun `echo headers are omitted when the challenge did not carry them`() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"captcha_key":["captcha-required"],"captcha_sitekey":"k","captcha_service":"hcaptcha"}"""),
        )
        server.enqueue(MockResponse().setBody("{}"))

        client(RecordingSolver { CaptchaSolution.of(it, "solved-token") }).newCall(post()).execute()

        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("solved-token", retry.getHeader("X-Captcha-Key"))
        assertNull(retry.getHeader("X-Captcha-Rqtoken"))
        assertNull(retry.getHeader("X-Captcha-Session-Id"))
    }

    @Test
    fun `a rejected solution is re-challenged, then given up on`() {
        repeat(CaptchaInterceptor.MAX_CHALLENGES + 1) { server.enqueue(challenge()) }
        val solver = RecordingSolver { CaptchaSolution.of(it, "solved-token") }

        val response = client(solver).newCall(post()).execute()

        assertEquals(400, response.code)
        assertEquals(CaptchaInterceptor.MAX_CHALLENGES, solver.challenges.size)
        // The caller still gets the body, so it can classify the failure.
        assertTrue(response.body!!.string().contains("captcha_key"))
    }

    @Test
    fun `declining the challenge fails the request rather than sending it`() {
        server.enqueue(challenge())
        val solver = RecordingSolver {
            throw CaptchaCancellationException(CaptchaCancellationException.REASON_DECLINED)
        }

        val failure = runCatching { client(solver).newCall(post()).execute() }.exceptionOrNull()

        assertTrue(failure is CaptchaCancellationException)
        assertEquals(CaptchaCancellationException.REASON_DECLINED, (failure as CaptchaCancellationException).reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an ordinary 400 is passed through untouched`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":50035,"message":"nope"}"""))
        val solver = RecordingSolver { CaptchaSolution.of(it, "solved-token") }

        val response = client(solver).newCall(post()).execute()

        assertEquals(400, response.code)
        assertTrue(solver.challenges.isEmpty())
        assertEquals("""{"code":50035,"message":"nope"}""", response.body!!.string())
    }

    @Test
    fun `a caller that opted out gets the challenge back instead of a prompt`() {
        server.enqueue(challenge())
        val solver = RecordingSolver { CaptchaSolution.of(it, "solved-token") }

        val response = client(solver)
            .newCall(post().newBuilder().tag(CaptchaRequestPolicy::class.java, CaptchaRequestPolicy.preSolved(null)).build())
            .execute()

        assertEquals(400, response.code)
        assertTrue(solver.challenges.isEmpty())
    }

    @Test
    fun `a pre-solved policy sends its headers on the first attempt`() {
        server.enqueue(MockResponse().setBody("{}"))
        val policy = CaptchaRequestPolicy.preSolved(CaptchaSolution("pre-solved", "rq", "session"))

        client(RecordingSolver { CaptchaSolution.of(it, "unused") })
            .newCall(post().newBuilder().tag(CaptchaRequestPolicy::class.java, policy).build())
            .execute()

        val sent = server.takeRequest()
        assertEquals("pre-solved", sent.getHeader("X-Captcha-Key"))
        assertEquals("rq", sent.getHeader("X-Captcha-Rqtoken"))
        assertEquals("session", sent.getHeader("X-Captcha-Session-Id"))
    }

    @Test
    fun `with no solver installed the challenge is handed back`() {
        server.enqueue(challenge())

        val response = client(solver = null).newCall(post()).execute()

        assertEquals(400, response.code)
        assertEquals(1, server.requestCount)
    }

    private fun client(solver: CaptchaSolver?) = OkHttpClient.Builder()
        .addInterceptor(CaptchaInterceptor { solver })
        .build()

    private fun post() = Request.Builder()
        .url(server.url("/api/v9/channels/1/messages"))
        .post("""{"content":"hi"}""".toRequestBody())
        .build()

    private fun challenge() = MockResponse().setResponseCode(400).setBody(
        """
        {
          "captcha_key": ["captcha-required"],
          "captcha_sitekey": "test-site-key",
          "captcha_service": "hcaptcha",
          "captcha_rqdata": "enterprise-rqdata",
          "captcha_rqtoken": "test-rqtoken",
          "captcha_session_id": "test-session",
          "should_serve_invisible": true,
          "user_flow": "register"
        }
        """.trimIndent(),
    )

    private class RecordingSolver(
        private val answer: (CaptchaErrorBody) -> CaptchaSolution,
    ) : CaptchaSolver {
        val challenges = mutableListOf<CaptchaErrorBody>()

        override fun solve(challenge: CaptchaErrorBody): CaptchaSolution {
            challenges += challenge
            return answer(challenge)
        }
    }
}
