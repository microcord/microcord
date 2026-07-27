package com.discord.widgets.auth

import com.discord.api.auth.OAuthScope
import com.discord.api.auth.OAuthScopeTypeAdapter
import com.discord.views.OAuthPermissionViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parsing and the two refusal rules of the OAuth authorization screen — the parts that
 * decide whether a third-party app gets access to the account.
 *
 * Exercised through the parameter-map overload of `parse` so no Android runtime is needed; the
 * `Uri` overload only decomposes the URL and delegates here.
 */
class OAuth2AuthorizeTest {

    private fun parse(vararg parameters: Pair<String, String>) =
        WidgetOauth2Authorize.parse(parameters.toMap())

    @Test
    fun `parses every authorize parameter`() {
        val request = parse(
            "client_id" to "123",
            "state" to "xyz",
            "response_type" to "code",
            "redirect_uri" to "https://example.com/cb",
            "scope" to "identify email",
            "permissions" to "8",
            "code_challenge" to "abc",
            "code_challenge_method" to "S256",
            "internal_referrer" to "true",
        )

        assertEquals(123L, request.clientId)
        assertEquals("xyz", request.state)
        assertEquals("code", request.responseType)
        assertEquals("https://example.com/cb", request.redirectUrl)
        assertEquals("identify email", request.scope)
        assertEquals(listOf("identify", "email"), request.scopeNames)
        assertEquals("8", request.permissions)
        assertEquals("S256", request.codeChallengeMethod)
        assertEquals("true", request.internalReferrer)
        // `prompt` is not read off the URL; the client always asks for consent.
        assertEquals("consent", request.prompt)
    }

    @Test
    fun `the PKCE challenge is never rendered`() {
        val request = parse("client_id" to "1", "scope" to "identify", "code_challenge" to "s3cret")
        assertFalse(request.toString().contains("s3cret"))
        assertTrue(request.toString().contains("codeChallenge=<redacted>"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a request without a client id is rejected`() {
        parse("scope" to "identify")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a request without a scope is rejected`() {
        parse("client_id" to "1")
    }

    @Test
    fun `the missing parameter is named so the error can say which`() {
        val failure = runCatching { parse("client_id" to "1") }.exceptionOrNull()
        assertEquals("scope", failure?.message)
    }

    @Test
    fun `bot and webhook grants are refused in-app`() {
        assertTrue(parse("client_id" to "1", "scope" to "bot").isUnsupported)
        assertTrue(parse("client_id" to "1", "scope" to "webhook.incoming").isUnsupported)
        assertTrue(parse("client_id" to "1", "scope" to "identify BOT").isUnsupported)
        assertFalse(parse("client_id" to "1", "scope" to "identify email").isUnsupported)
    }

    @Test
    fun `an unknown scope has no description and is reported by its wire name`() {
        val scope = OAuthScopeTypeAdapter.scopeOf("some.future.scope")
        assertEquals(OAuthScope.Invalid("some.future.scope"), scope)

        val failure = runCatching { OAuthPermissionViews.scopePermissionStringRes(scope) }.exceptionOrNull()
        assertTrue(failure is OAuthPermissionViews.InvalidScopeException)
        assertEquals("some.future.scope", (failure as OAuthPermissionViews.InvalidScopeException).scope)
    }

    @Test
    fun `every known scope resolves to a description`() {
        val known = listOf(
            "identify", "email", "connections", "guilds", "guilds.join", "guilds.members.read",
            "gdm.join", "rpc", "rpc.notifications.read", "rpc.voice.read", "rpc.voice.write",
            "rpc.activities.write", "bot", "webhook.incoming", "messages.read",
            "applications.builds.upload", "applications.builds.read", "applications.commands",
            "applications.commands.update", "applications.store.update", "applications.entitlements",
            "activities.read", "activities.write", "relationships.read", "voice", "dm_channels.read",
        )
        known.forEach { name ->
            val scope = OAuthScopeTypeAdapter.scopeOf(name)
            assertFalse("$name should be a known scope", scope is OAuthScope.Invalid)
            assertEquals(name, scope.scopeName)
            OAuthPermissionViews.scopePermissionStringRes(scope)
        }
    }

    @Test
    fun `optional parameters default rather than throwing`() {
        val request = parse("client_id" to "42", "scope" to "identify")
        assertNull(request.state)
        assertNull(request.redirectUrl)
        assertNull(request.codeChallenge)
        assertEquals(listOf("identify"), request.scopeNames)
    }
}
