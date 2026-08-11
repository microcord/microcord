package com.discord.utilities.feature

import com.discord.utilities.feature.ChatInputCustomization.Button
import com.discord.utilities.feature.ChatInputCustomization.Entry
import com.discord.utilities.feature.ChatInputCustomization.Zone
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputCustomizationTest {
    @Test
    fun layoutRoundTripsAndRepairsMissingEntries() {
        val layout = listOf(Entry(Button.SEND, Zone.LEFT), Entry(Button.PROFILE, Zone.HIDDEN))

        assertEquals(layout, ChatInputCustomization.decode(ChatInputCustomization.encode(layout)).take(2))
        assertEquals(Button.entries.toSet(), ChatInputCustomization.decode("broken").map(Entry::button).toSet())
    }
}
