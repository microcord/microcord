package com.discord.utilities.textprocessing

import com.discord.api.emoji.GuildEmoji
import com.discord.models.domain.emoji.EmojiCategory
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode
import com.discord.widgets.chat.input.emoji.EmojiCatalog
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
        val categories = EmojiCatalog.parse(
            """{"people":[{"names":["wave","hello"],"surrogates":"👋","unicodeVersion":6}]}""",
        )
        assertEquals(EmojiCategory.PEOPLE, categories.keys.single())
        assertEquals("👋", categories.values.single().single().getMessageContentReplacement())
        assertEquals(
            "<a:party:42>",
            ModelEmojiCustom(GuildEmoji(42, "party", null, true, false, true, true), 7L)
                .getMessageContentReplacement(),
        )
    }
}
