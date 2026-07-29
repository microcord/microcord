package com.discord.widgets.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSelectionTypesTest {
    @Test
    fun constantsMatch12621AnalyticsValues() {
        assertEquals(
            listOf("CHANNEL", "CHOICE", "COMMAND", "EMOJI", "GIF", "MENTION", "REACTION", "STICKER"),
            listOf(
                AutocompleteSelectionTypes.CHANNEL,
                AutocompleteSelectionTypes.CHOICE,
                AutocompleteSelectionTypes.COMMAND,
                AutocompleteSelectionTypes.EMOJI,
                AutocompleteSelectionTypes.GIF,
                AutocompleteSelectionTypes.MENTION,
                AutocompleteSelectionTypes.REACTION,
                AutocompleteSelectionTypes.STICKER,
            ),
        )
        assertEquals(
            listOf("CHANNELS", "CHOICE", "COMMANDS", "EMOJIS_AND_STICKERS", "GIFS", "MENTIONS", "REACTIONS"),
            listOf(
                AutocompleteTypes.CHANNELS,
                AutocompleteTypes.CHOICE,
                AutocompleteTypes.COMMANDS,
                AutocompleteTypes.EMOJIS_AND_STICKERS,
                AutocompleteTypes.GIFS,
                AutocompleteTypes.MENTIONS,
                AutocompleteTypes.REACTIONS,
            ),
        )
        assertEquals(listOf("emoji", "sticker"), listOf(ExpressionSelectionTypes.EMOJI, ExpressionSelectionTypes.STICKER))
    }
}
