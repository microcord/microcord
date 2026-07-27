package com.discord.widgets.captcha

import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The captcha challenge description: how it is read off the wire, and that its two secret-ish
 * fields never make it into a log line.
 */
class CaptchaErrorBodyTest {

    @Test
    fun `parses a snake-cased challenge body`() {
        val body = CaptchaErrorBody.createFromString(
            """
            {
              "captcha_key": ["captcha-required"],
              "captcha_sitekey": "site-key-1234",
              "captcha_service": "hcaptcha",
              "captcha_rqdata": "enterprise-blob",
              "captcha_rqtoken": "rq-token-5678"
            }
            """.trimIndent(),
        )

        assertEquals(listOf("captcha-required"), body?.captchaKey)
        assertEquals("site-key-1234", body?.captchaSitekey)
        assertEquals(CaptchaService.HCAPTCHA, body?.captchaService)
        assertEquals("enterprise-blob", body?.captchaRqdata)
        assertEquals("rq-token-5678", body?.captchaRqtoken)
    }

    @Test
    fun `recaptcha is recognised so the hcaptcha path is not taken`() {
        val body = CaptchaErrorBody.createFromString("""{"captcha_service":"recaptcha"}""")
        assertEquals(CaptchaService.RECAPTCHA, body?.captchaService)
        assertEquals(CaptchaService.RECAPTCHA, CaptchaService.fromWireName("reCAPTCHA"))
        assertNull(CaptchaService.fromWireName("turnstile"))
        assertNull(CaptchaService.fromWireName(null))
    }

    @Test
    fun `an unparseable body yields null rather than throwing`() {
        assertNull(CaptchaErrorBody.createFromString("not json at all"))
    }

    @Test
    fun `the request token and enterprise blob are redacted`() {
        val rendered = CaptchaErrorBody(
            captchaKey = listOf("captcha-required"),
            captchaSitekey = "site-key-1234",
            captchaService = CaptchaService.HCAPTCHA,
            captchaRqdata = "enterprise-blob",
            captchaRqtoken = "rq-token-5678",
        ).toString()

        assertFalse(rendered.contains("enterprise-blob"))
        assertFalse(rendered.contains("rq-token-5678"))
        assertTrue(rendered.contains("captchaRqdata=<redacted>"))
        assertTrue(rendered.contains("captchaRqtoken=<redacted>"))
        // The site key is public by design — it is embedded in the challenge page itself.
        assertTrue(rendered.contains("site-key-1234"))
    }

    @Test
    fun `a closed challenge is distinguishable from a failed one`() {
        val closed = HCaptchaWebDialog.Outcome.Failed(HCaptchaWebDialog.CHALLENGE_CLOSED)
        val networkFailure = HCaptchaWebDialog.Outcome.Failed(HCaptchaWebDialog.NETWORK_ERROR)

        assertTrue(closed.isUserClosed)
        assertFalse(networkFailure.isUserClosed)
    }

    @Test
    fun `a solved token is never rendered`() {
        val rendered = HCaptchaWebDialog.Outcome.Solved("P0_eyJ0eXAiOiJKV1Qi").toString()
        assertFalse(rendered.contains("P0_eyJ0eXAiOiJKV1Qi"))
        assertEquals("Solved(token=<redacted>)", rendered)
    }
}
