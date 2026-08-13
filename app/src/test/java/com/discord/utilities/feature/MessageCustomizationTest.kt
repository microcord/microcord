package com.discord.utilities.feature

import com.discord.models.message.Message
import com.discord.utilities.feature.MessageCustomization.Entry
import com.discord.utilities.feature.MessageCustomization.Section
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.entries.ThreadStarterDividerEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageCustomizationTest {
    @Test
    fun layoutRoundTripsAndRepairsMissingSections() {
        val layout = listOf(Entry(Section.REACTIONS), Entry(Section.CORE, visible = false))

        assertEquals(layout, MessageCustomization.decode(MessageCustomization.encode(layout)).take(2))
        assertEquals(Section.entries.toSet(), MessageCustomization.decode("broken").map(Entry::section).toSet())
    }

    @Test
    fun applyFiltersAndReordersStableWithinSections() {
        val message = Message(id = 1L, channelId = 2L, type = 0)
        val core = MessageEntry(
            message = message,
            messageState = null,
            author = null,
            roles = null,
            nickOrUsernames = emptyMap(),
            isExpandedBlocked = false,
            permissionsForChannel = null,
        )
        val reactions = ReactionsEntry(message, true, true, true)
        val thread = ThreadStarterDividerEntry(2L, 1L)
        val layout = listOf(
            Entry(Section.REACTIONS),
            Entry(Section.CORE),
            Entry(Section.THREAD, visible = false),
        )

        assertEquals(listOf(reactions, core), MessageCustomization.apply(listOf(core, thread, reactions), layout))
    }
}
