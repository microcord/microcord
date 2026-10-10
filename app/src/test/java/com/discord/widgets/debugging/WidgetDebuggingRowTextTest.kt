package com.discord.widgets.debugging

import com.discord.app.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app log viewer is a diagnostics surface, and long-pressing a row copies exactly the text
 * it renders onto the clipboard. Whatever [logRowText] returns is therefore the
 * whole of what this screen can leak.
 */
class WidgetDebuggingRowTextTest {

    private val secret = "mfa.VeryS3cretValue-1234567890"

    private fun item(message: String, throwable: Throwable? = null) =
        AppLog.LoggedItem(priority = android.util.Log.WARN, message = message, throwable = throwable)

    @Test
    fun `a plain line renders exactly the recorded message`() {
        assertEquals("Gateway reconnecting", logRowText(item("Gateway reconnecting")))
    }

    @Test
    fun `a throwable is appended on its own line`() {
        val text = logRowText(item("Send failed", IllegalStateException("no socket")))
        assertEquals("Send failed\njava.lang.IllegalStateException: no socket", text)
    }

    /**
     * `AppLog` redacts the message before recording it but keeps the [Throwable] untouched, so the
     * render site has to redact too — otherwise an exception quoting a request would print the
     * credential in the clear and hand it to the clipboard. This is a deliberate divergence from
     * the reference, which appends `"\n$throwable"` raw.
     */
    @Test
    fun `a secret inside the throwable message is redacted`() {
        val text = logRowText(
            item(
                message = AppLog.redactSecrets("""POST /auth/login {"password":"hunter2"}"""),
                throwable = IllegalStateException("""rejected: {"token":"$secret"}"""),
            ),
        )
        assertFalse(text, text.contains(secret))
        assertFalse(text, text.contains("hunter2"))
        assertTrue(text, text.contains(AppLog.REDACTED))
    }

    @Test
    fun `a line without a throwable stays a single line`() {
        val text = logRowText(item("nothing thrown"))
        assertFalse(text.contains("\n"))
    }

    @Test
    fun `checked filter hides verbose and preserves tagged diagnostics`() {
        val logs = listOf(
            AppLog.LoggedItem(android.util.Log.VERBOSE, "Gateway -> heartbeat", null),
            AppLog.LoggedItem(android.util.Log.DEBUG, "HTTP -> GET /users/@me", null),
            AppLog.LoggedItem(android.util.Log.ERROR, "RTC -> disconnected", null),
        )

        assertEquals(
            listOf("HTTP -> GET /users/@me", "RTC -> disconnected"),
            filterDebugLogs(logs, isFiltered = true).map { it.message },
        )
        assertEquals(logs, filterDebugLogs(logs, isFiltered = false))
    }

    @Test
    fun `saved logs use the same secret-safe row rendering`() {
        val exported = debugLogExport(
            listOf(item("request failed", IllegalStateException("token=$secret"))),
        )

        assertTrue(exported.startsWith("Microcord debug log\n"))
        assertFalse(exported.contains(secret))
        assertTrue(exported.contains(AppLog.REDACTED))
    }

    @Test
    fun `filtered view hides system rows but export retains them`() {
        val system = AppLog.LoggedItem(android.util.Log.WARN, "OpenGLRenderer warning", null, isSystem = true)

        assertTrue(filterDebugLogs(listOf(system), isFiltered = true).isEmpty())
        assertEquals(listOf(system), filterDebugLogs(listOf(system), isFiltered = true, showSystemLogs = true))
        assertTrue(debugLogExport(listOf(system)).contains("OpenGLRenderer warning"))
    }

    @Test
    fun `header sits between the title and the log rows`() {
        val exported = debugLogExport(listOf(item("row", null)), header = "Device: Test Phone")

        assertTrue(exported.startsWith("Microcord debug log\nDevice: Test Phone\n\nrow"))
    }

}
