package com.discord.utilities.feature

import com.discord.utilities.feature.HeaderCustomization.Action
import com.discord.utilities.feature.HeaderCustomization.Entry
import com.discord.utilities.feature.HeaderCustomization.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderCustomizationTest {
    @Test
    fun layoutRoundTripsAndRepairsMissingEntries() {
        val layout = listOf(Entry(Action.MEMBERS), Entry(Action.VOICE, visible = false))

        assertEquals(layout, HeaderCustomization.decode(Surface.DM, HeaderCustomization.encode(layout)).take(2))
        assertEquals(Action.entries.toSet(), HeaderCustomization.decode(Surface.DM, "broken").map(Entry::action).toSet())
        assertEquals(
            setOf(Action.SEARCH, Action.MEMBERS, Action.PINS),
            HeaderCustomization.decode(Surface.CHANNEL, "VOICE:false").map(Entry::action).toSet(),
        )
    }
}
