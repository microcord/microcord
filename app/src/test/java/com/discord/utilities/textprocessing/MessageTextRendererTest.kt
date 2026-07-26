package com.discord.utilities.textprocessing

import com.discord.api.emoji.GuildEmoji
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode
import com.discord.widgets.chat.input.emoji.WidgetEmojiPicker
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTextRendererTest {

    @Test
    fun plainTextDropsTheTrailingZeroWidthSpaceUsedByRenderedMessages() {
        assertEquals(
            "hello",
            MessageTextRenderer.toPlainText("hello" + ZeroSpaceWidthNode.ZERO_WIDTH_SPACE),
        )
        assertEquals("hello", MessageTextRenderer.toPlainText("  hello  "))
    }

    @Test
    fun recoveredCatalogAndCustomEmojiProduceComposerText() {
        val categories = WidgetEmojiPicker.EmojiCatalog.parse(
            """{"people":[{"names":["wave","hello"],"surrogates":"👋","unicodeVersion":6}]}""",
        )
        assertEquals("people", categories.single().key)
        assertEquals("👋", categories.single().items.single().inputText)
        assertEquals(
            "<a:party:42>",
            WidgetEmojiPicker.PickerEmoji.Custom(
                GuildEmoji(42, "party", null, true, false, true, true),
            ).inputText,
        )
    }
}
