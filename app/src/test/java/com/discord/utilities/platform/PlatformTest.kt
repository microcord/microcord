package com.discord.utilities.platform

import com.discord.api.connectedaccounts.ConnectedAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformTest {

    @Test fun `wire names that a constant cannot spell still resolve`() {
        assertEquals(Platform.AMAZON_MUSIC, Platform.from("amazon-music"))
        assertEquals(Platform.PLAYSTATION_STAGING, Platform.from("playstation-stg"))
        assertEquals(Platform.EPIC_GAMES, Platform.from("epicgames"))
        assertEquals(Platform.RIOT_GAMES, Platform.from("riotgames"))
        assertEquals(Platform.META_QUEST_OR_HORIZON, Platform.from("meta_quest_or_horizon"))
        assertEquals(Platform.LEAGUEOFLEGENDS, Platform.from("leagueoflegends"))
    }

    @Test fun `an unheard-of service resolves rather than throwing`() {
        assertEquals(Platform.NONE, Platform.from("myspace"))
        assertEquals(Platform.NONE, Platform.from(""))
        assertEquals(Platform.NONE, Platform.from("  "))
    }

    @Test fun `every platform id is unique and lower case`() {
        val ids = Platform.entries.map { it.platformId }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it == it.lowercase() })
        Platform.entries.forEach { assertEquals(it, Platform.from(it.platformId)) }
    }

    @Test fun `profile links point at the service the account lives on`() {
        assertEquals("https://x.com/manti", Platform.TWITTER.getProfileUrl(account(name = "manti")))
        assertEquals("https://roblox.com/users/42/profile", Platform.ROBLOX.getProfileUrl(account(id = "42")))
        assertEquals("https://www.ebay.com/usr/manti", Platform.EBAY.getProfileUrl(account(name = "manti")))
        assertEquals("https://www.tiktok.com/@manti", Platform.TIKTOK.getProfileUrl(account(name = "manti")))
        assertEquals("https://example.com/", Platform.DOMAIN.getProfileUrl(account(id = "example.com")))
        assertEquals("https://mas.to/@manti", Platform.MASTODON.getProfileUrl(account(id = "https://mas.to/@manti")))
        assertNull(Platform.PAYPAL.getProfileUrl(account(name = "manti")))
        assertNull(Platform.NONE.getProfileUrl(account(name = "manti")))
    }

    private fun account(id: String = "1", name: String = "name") =
        ConnectedAccount(id = id, name = name)
}
