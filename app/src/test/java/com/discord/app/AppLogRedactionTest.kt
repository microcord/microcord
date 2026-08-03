package com.discord.app

import android.util.Log
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

    @Test
    fun `log snapshot is bounded and preserves oldest to newest order`() = withLogging {
        repeat(AppLog.MAX_BUFFERED_LOGS + 1) { AppLog.d("line-$it") }

        val logs = AppLog.getLogs()
        assertEquals(AppLog.MAX_BUFFERED_LOGS, logs.size)
        assertEquals("line-1", logs.first().message)
        assertEquals("line-${AppLog.MAX_BUFFERED_LOGS}", logs.last().message)
    }

    @Test
    fun `new lifecycle subscriber receives logs recorded while detached`() = withLogging {
        val first = mutableListOf<List<AppLog.LoggedItem>>()
        val firstSubscription = AppLog.observeLogs().subscribe(first::add)
        AppLog.w("while attached")
        firstSubscription.unsubscribe()
        AppLog.e("while detached")

        val restored = AppLog.observeLogs().take(1).toBlocking().single()

        assertEquals(listOf("while attached", "while detached"), restored.takeLast(2).map { it.message })
    }

    private fun withLogging(block: () -> Unit) {
        val previousPriority = AppLog.minimumPriority
        val previousSink = AppLog.sink
        AppLog.minimumPriority = Log.VERBOSE
        AppLog.sink = { _, _, _, _ -> }
        try {
            block()
        } finally {
            AppLog.minimumPriority = previousPriority
            AppLog.sink = previousSink
        }
    }
}
