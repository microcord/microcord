package com.discord.widgets.servers.guildboost

import org.junit.Assert.assertEquals
import org.junit.Test

class GuildBoostPerkViewAdapterTest {

    @Test
    fun `perk item identity is its rendered text`() {
        val item = GuildBoostPerkViewAdapter.GuildBoostPerkViewListItem(42, "More emoji")

        assertEquals(1, item.type)
        assertEquals("More emoji", item.key)
    }
}
