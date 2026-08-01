package com.discord.utilities.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkParserTest {
    @Test
    fun parsesInviteGuildChannelMessageUserAndSettingsRoutes() {
        assertEquals(
            DeepLinkRoute.Invite("abc-123", 42, "share"),
            internal("https://discord.gg/abc-123?event=42&source=share"),
        )
        assertEquals(DeepLinkRoute.Guild(10), internal("https://discord.com/channels/10"))
        assertEquals(DeepLinkRoute.Channel(10, 20, 30), internal("discord://app/channels/10/20/30"))
        assertEquals(DeepLinkRoute.Channel(null, 20, null), internal("manticord://app/channels/@me/20"))
        assertEquals(DeepLinkRoute.DirectMessage(40), internal("https://discord.com/channels/@me/user/40"))
        assertEquals(DeepLinkRoute.User(50, "friend"), internal("https://discord.com/users/50?friend_token=friend"))
        assertEquals(DeepLinkRoute.Settings("account"), internal("https://discord.com/settings/account"))
        assertEquals(DeepLinkRoute.Friends, internal("https://discord.com/feature/friends"))
        assertEquals(DeepLinkRoute.Home, internal("discord://app"))
    }

    @Test
    fun parsesRemainingReferenceLinkFamilies() {
        val routes = mapOf(
            "https://discord.com/discovery" to DeepLinkRoute.Discovery,
            "https://discord.gift/gift" to DeepLinkRoute.Gift("gift"),
            "https://discord.new/template" to DeepLinkRoute.GuildTemplate("template"),
            "https://discord.com/channels/@me/-1" to DeepLinkRoute.Friends,
            "discord://app/connect" to DeepLinkRoute.Voice(null),
            "discord://app/connect/20" to DeepLinkRoute.Voice(20),
            "discord://app/lurk/10" to DeepLinkRoute.Lurk(10, null),
            "discord://app/lurk/10/20" to DeepLinkRoute.Lurk(10, 20),
            "discord://app/ra/fingerprint" to DeepLinkRoute.RemoteAuth("fingerprint"),
            "discord://app/guild/@me/premiumguild" to DeepLinkRoute.PremiumGuild(null),
            "discord://app/guild/10/premiumguild" to DeepLinkRoute.PremiumGuild(10),
            "https://discord.com/events/10/20" to DeepLinkRoute.Event(10, 20),
            "https://discord.com/channels/10/role-subscriptions" to
                DeepLinkRoute.StaticChannel(10, "role-subscriptions"),
        )
        routes.forEach { (uri, route) -> assertEquals(uri, route, internal(uri)) }
        assertTrue(internal("discord://action/oauth2/authorize?client_id=1") is DeepLinkRoute.Authorization)
        assertTrue(internal("https://discord.com/query/?type=server&serverName=Manticord") is DeepLinkRoute.Query)
    }

    @Test
    fun rejectsMalformedUnsafeAndUnsupportedRoutes() {
        assertUnsupported("http://discord.com/channels/1/2", LinkParseResult.Reason.UNSAFE_SCHEME)
        assertUnsupported("file:///tmp/token", LinkParseResult.Reason.UNSAFE_SCHEME)
        assertUnsupported("javascript:alert(1)", LinkParseResult.Reason.UNSAFE_SCHEME)
        assertUnsupported("https://discord.com@evil.example/channels/1/2", LinkParseResult.Reason.UNSUPPORTED_HOST)
        assertUnsupported("https://discord.com/channels/not-a-guild/2", LinkParseResult.Reason.UNSUPPORTED_PATH)
        assertUnsupported("https://discord.com/channels/1/2/not-a-message", LinkParseResult.Reason.UNSUPPORTED_PATH)
        assertUnsupported("https://discord.com/settings/unknown", LinkParseResult.Reason.UNSUPPORTED_PATH)
        assertUnsupported("discord://user@app/channels/1/2", LinkParseResult.Reason.UNSUPPORTED_HOST)
        assertUnsupported("discord://app/connect/nope", LinkParseResult.Reason.UNSUPPORTED_PATH)
        assertUnsupported("discord://app/lurk/1/nope", LinkParseResult.Reason.UNSUPPORTED_PATH)
        assertUnsupported("https://user:pass@discord.com/channels/1/2", LinkParseResult.Reason.UNSUPPORTED_HOST)
        assertUnsupported("https://discord.com:8443/channels/1/2", LinkParseResult.Reason.UNSUPPORTED_HOST)
        assertUnsupported("https://discord.com/invite/code?source=%", LinkParseResult.Reason.MALFORMED)
    }

    @Test
    fun externalPolicyAllowsOnlyCredentialFreeHttpsUris() {
        assertEquals(LinkParseResult.External("https://example.com/path"), DeepLinkParser.parse("https://example.com/path"))
        assertTrue(ExternalUriPolicy.isAllowed("https://example.com/path"))
        assertFalse(ExternalUriPolicy.isAllowed("https://user:pass@example.com/path"))
        assertFalse(ExternalUriPolicy.isAllowed("content://provider/item"))
        assertFalse(ExternalUriPolicy.isAllowed("intent://example.com/#Intent;scheme=https;end"))
        assertFalse(ExternalUriPolicy.isAllowed("https://localhost/path"))
        assertFalse(ExternalUriPolicy.isAllowed("https://localhost./path"))
        assertFalse(ExternalUriPolicy.isAllowed("https://127.0.0.1/path"))
        assertFalse(ExternalUriPolicy.isAllowed("https://[::1]/path"))
        assertFalse(ExternalUriPolicy.isAllowed("https://example.com:8443/path"))
        assertFalse(ExternalUriPolicy.isAllowed("https://example.com\\@evil.test/path"))
        assertEquals(
            LinkParseResult.External("https://support.discord.com/app"),
            DeepLinkParser.parse("https://support.discord.com/app"),
        )
    }

    private fun internal(uri: String) = (DeepLinkParser.parse(uri) as LinkParseResult.Internal).route

    private fun assertUnsupported(uri: String, reason: LinkParseResult.Reason) {
        assertEquals(LinkParseResult.Unsupported(reason), DeepLinkParser.parse(uri))
    }
}
