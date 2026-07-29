package com.discord.widgets.guilds.profile

import com.discord.models.domain.emoji.ModelEmojiCustom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGuildProfileSheetEmojisAdapterTest {
    @Test fun `overflow replaces the final emoji and caps its count`() {
        val emojis = (1L..120L).map(::emoji)

        val items = WidgetGuildProfileSheetEmojisAdapter.getEmojiItems(emojis, 4)

        assertEquals(4, items.size)
        assertEquals(listOf(1L, 2L, 3L), items.filterIsInstance<EmojiItem.EmojiData>().map {
            (it.emoji as ModelEmojiCustom).id
        })
        assertEquals(99, (items.last() as EmojiItem.MoreEmoji).extraEmojiCount)
    }

    @Test fun `all emojis remain when they fit`() {
        val items = WidgetGuildProfileSheetEmojisAdapter.getEmojiItems(listOf(emoji(1), emoji(2)), 4)

        assertEquals(2, items.size)
        assertTrue(items.all { it is EmojiItem.EmojiData })
    }

    private fun emoji(id: Long) = ModelEmojiCustom(
        id, "emoji$id", emptyList(), true, false, 0, null, false, true, true, 1,
    )
}
