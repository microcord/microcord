package com.discord.utilities.textprocessing

import com.discord.api.emoji.GuildEmoji
import com.discord.api.role.GuildRole
import com.discord.widgets.chat.input.emoji.WidgetEmojiPicker
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTextRendererTest {
    @Test
    fun resolvesMentionsAndCustomEmojiWithoutLosingText() {
        val role = GuildRole(1, "Moderators", 3, 0x123456, false, 0, true, false, null, null, null)

        assertEquals(
            listOf(
                MessageTextRenderer.Segment.Text("Hi "),
                MessageTextRenderer.Segment.Mention("@Ada"),
                MessageTextRenderer.Segment.Text(" in "),
                MessageTextRenderer.Segment.Mention("#general"),
                MessageTextRenderer.Segment.Text(" with "),
                MessageTextRenderer.Segment.Mention("@Moderators", 0x123456),
                MessageTextRenderer.Segment.Text(" "),
                MessageTextRenderer.Segment.CustomEmoji("party", 4, true),
                MessageTextRenderer.Segment.Text(" "),
                MessageTextRenderer.Segment.Mention("@everyone"),
            ),
            MessageTextRenderer.resolve(
                "Hi <@1> in <#2> with <@&3> <a:party:4> @everyone",
                mapOf(1L to "Ada"),
                mapOf(2L to "general"),
                mapOf(3L to role),
            ),
        )
        assertEquals("bold and code", MessageTextRenderer.stripInlineMarkdown("**bold** and `code`"))
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
