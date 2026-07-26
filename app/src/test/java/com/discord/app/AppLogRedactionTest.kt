package com.discord.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The project contract forbids writing credentials or message content to any log sink. */
class AppLogRedactionTest {

    private val secret = "mfa.VeryS3cretValue-1234567890"

    @Test
    fun `json style secrets are masked`() {
        val redacted = AppLog.redactSecrets("""{"token":"$secret","username":"wumpus"}""")
        assertFalse(redacted.contains(secret))
        assertEquals("""{"token":${AppLog.REDACTED},"username":"wumpus"}""", redacted)
    }

    @Test
    fun `query and header style secrets are masked`() {
        listOf(
            "authorization: $secret",
            "Cookie=$secret; Path=/",
            "password=hunter2&username=wumpus",
            "mfa_code: 123456",
            "captcha_key=abc.def",
        ).forEach { line ->
            val redacted = AppLog.redactSecrets(line)
            assertFalse("not redacted: $redacted", redacted.contains(secret))
            assertFalse("not redacted: $redacted", redacted.contains("hunter2"))
            assertFalse("not redacted: $redacted", redacted.contains("123456"))
            assertFalse("not redacted: $redacted", redacted.contains("abc.def"))
        }
    }

    @Test
    fun `authorization schemes are masked`() {
        val redacted = AppLog.redactSecrets("Retrying with Bearer $secret")
        assertEquals("Retrying with Bearer ${AppLog.REDACTED}", redacted)
    }

    @Test
    fun `message content is masked`() {
        val redacted = AppLog.redactSecrets("""{"channel_id":"12","content":"a private message"}""")
        assertFalse(redacted.contains("a private message"))
    }

    @Test
    fun `ordinary diagnostics are left intact`() {
        val message = "Gateway reconnecting, sequence=42, attempt 3"
        assertEquals(message, AppLog.redactSecrets(message))
    }
}
