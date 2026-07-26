package com.discord.utilities.uri

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UriHandlerSafetyTest {

    @After
    fun tearDown() {
        UriHandler.trustedDomainResolver = null
        UriHandler.untrustedLinkHandler = null
    }

    // region schemes

    @Test
    fun `ordinary navigation schemes are allowed`() {
        listOf(
            "https://discord.com/channels/1/2",
            "http://example.com",
            "market://details?id=com.discord",
            "mailto:someone@example.com",
            "tel:+15550100",
            "discord://ptb",
            "spotify:track:abc",
        ).forEach { assertTrue("expected $it to be allowed", UriHandler.isSafeScheme(it)) }
    }

    @Test
    fun `hostile schemes are rejected`() {
        listOf(
            "javascript:alert(document.cookie)",
            "JavaScript:alert(1)",
            "vbscript:msgbox(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///data/data/com.discord/shared_prefs/auth.xml",
            "content://com.discord.provider/private",
            "intent://scan/#Intent;scheme=zxing;package=com.evil;end",
            "android-app://com.evil",
        ).forEach { assertFalse("expected $it to be rejected", UriHandler.isSafeScheme(it)) }
    }

    @Test
    fun `a value with no scheme is rejected`() {
        assertFalse(UriHandler.isSafeScheme("evil.com/path"))
        assertFalse(UriHandler.isSafeScheme("//evil.com"))
        assertFalse(UriHandler.isSafeScheme(""))
        assertFalse(UriHandler.isSafeScheme(":no-scheme"))
    }

    // endregion

    // region host extraction

    @Test
    fun `host extraction ignores port, path, query and case`() {
        assertEquals("discord.com", UriHandler.getDomainName("https://Discord.com:443/channels/1?x=1#y"))
        assertEquals("example.com", UriHandler.getDomainName("http://example.com"))
        assertNull(UriHandler.getDomainName("mailto:someone@example.com"))
    }

    @Test
    fun `userinfo cannot disguise the real host`() {
        assertEquals("evil.com", UriHandler.getDomainName("https://discord.com@evil.com/path"))
        assertEquals("evil.com", UriHandler.getDomainName("https://user:pass@evil.com/"))
    }

    // endregion

    // region trust

    @Test
    fun `first-party hosts are implicitly trusted`() {
        listOf(
            "discord.com",
            "discordapp.com",
            "cdn.discordapp.com",
            "canary.discord.com",
            "discord.gg",
            "open.spotify.com",
        ).forEach { assertTrue("expected $it to be trusted", UriHandler.isImplicitlyTrustedDomain(it)) }
    }

    @Test
    fun `look-alike hosts are not trusted`() {
        listOf(
            "evil-discord.com",
            "discord.com.evil.com",
            "discordapp.com.attacker.net",
            "notdiscord.com",
            "xn--discord-hza.com",
        ).forEach { assertFalse("expected $it to be untrusted", UriHandler.isImplicitlyTrustedDomain(it)) }
    }

    @Test
    fun `masked links to unknown hosts are untrusted without a resolver`() {
        assertTrue(UriHandler.isTrustedDomain("https://discord.com/invite/abc"))
        assertFalse(UriHandler.isTrustedDomain("https://evil.com", mask = "https://discord.com"))
    }

    @Test
    fun `the store-supplied resolver can grant trust`() {
        UriHandler.trustedDomainResolver = { url, _ -> url.startsWith("https://trusted.example/") }
        assertTrue(UriHandler.isTrustedDomain("https://trusted.example/a"))
        assertFalse(UriHandler.isTrustedDomain("https://untrusted.example/a"))
    }

    @Test
    fun `an untrusted link is routed to the confirmation handler instead of being opened`() {
        var confirmedUrl: String? = null
        UriHandler.untrustedLinkHandler = { _, url -> confirmedUrl = url }
        UriHandler.handleOrUntrusted(FAKE_CONTEXT, "https://evil.com", mask = "https://discord.com")
        assertEquals("https://evil.com", confirmedUrl)
    }

    // endregion

    private companion object {
        /**
         * `handleOrUntrusted` must decide before touching the context; a stub proves the untrusted
         * branch never reaches Android code.
         */
        private val FAKE_CONTEXT = android.content.ContextWrapper(null)
    }
}
