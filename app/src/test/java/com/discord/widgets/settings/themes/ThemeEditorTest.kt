package com.discord.widgets.settings.themes

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeEditorTest {
    @Test
    fun labelsReadLikeWords() {
        assertEquals("Background primary", ThemeTokens.label("colorBackgroundPrimary"))
        assertEquals("Brand 500", ThemeTokens.label("color_brand_500"))
        assertEquals("Chat mentioned me", ThemeTokens.label("theme_chat_mentioned_me"))
        assertEquals("Neutral 66", ThemeTokens.label("neutral_66"))
        assertEquals("color", ThemeTokens.label("color"))
    }
}
