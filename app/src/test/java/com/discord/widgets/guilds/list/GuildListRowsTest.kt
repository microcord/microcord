package com.discord.widgets.guilds.list

import com.discord.models.guild.Guild
import com.discord.stores.StoreGuildsSorted
import org.junit.Assert.assertEquals
import org.junit.Test

class GuildListRowsTest {
    @Test fun aggregatesFolderReadStateAndProjectsExpandedGuilds() {
        val one = Guild(id = 1, name = "One")
        val two = Guild(id = 2, name = "Two")
        val three = Guild(id = 3, name = "Three")
        val rows = WidgetGuildsList.buildRows(
            listOf(
                StoreGuildsSorted.Entry.SingletonGuild(one),
                StoreGuildsSorted.Entry.Folder(10, listOf(two, three), null, "Folder"),
            ),
            expanded = setOf(10),
            selected = 3,
            unreadGuildIds = setOf(1, 3),
            mentionCounts = mapOf(1L to 2, 2L to 3, 3L to 4),
        )

        assertEquals(GuildRow.GuildItem(one, false, true, 2), rows[1])
        assertEquals(GuildRow.Folder(10, "Folder", null, listOf(two, three), true, true, 7), rows[2])
        assertEquals(GuildRow.GuildItem(two, false, false, 3), rows[3])
        assertEquals(GuildRow.GuildItem(three, true, true, 4), rows[4])
    }
}
