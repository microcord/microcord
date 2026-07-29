package com.discord.widgets.chat.input.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiPickerModeTest {
    @Test
    fun `only the bottom sheet owns an editable search bar`() {
        assertFalse(EmojiPickerMode.INLINE.showsSearchBar)
        assertTrue(EmojiPickerMode.BOTTOM_SHEET.showsSearchBar)
    }

    @Test
    fun `guild context retains the exact guild id`() {
        assertEquals(42L, EmojiPickerContextType.Guild(42L).guildId)
    }
}
