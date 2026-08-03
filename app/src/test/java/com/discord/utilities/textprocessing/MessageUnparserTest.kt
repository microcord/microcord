package com.discord.utilities.textprocessing

import com.discord.models.domain.emoji.EmojiSet
import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageUnparserTest {
    @Test
    fun resolvesKnownUsersAndPreservesUnknownEntities() {
        val users = mapOf(42L to CoreUser(id = 42, username = "Ada", discriminator = 7))
        val emojis = EmojiSet(emptyMap(), emptyMap(), emptyMap(), emptyList(), emptySet())

        val result = MessageUnparser.unparse(
            "hello <@42> <@99> <:wave:123>",
            guild = null,
            channels = emptyMap(),
            users = users,
            emojiSet = emojis,
        )

        assertEquals("hello @Ada#0007 <@99> :wave:", result.toString())
    }

    @Test
    fun preservesCommandAndTimestampWireFormsWhileUnparsingEmoji() {
        val emojis = EmojiSet(emptyMap(), emptyMap(), emptyMap(), emptyList(), emptySet())

        val result = MessageUnparser.unparse(
            "</weather now:123> <t:1700000000:R> <a:wave:456>",
            guild = null,
            channels = emptyMap(),
            users = emptyMap(),
            emojiSet = emojis,
        )

        assertEquals("</weather now:123> <t:1700000000:R> :wave:", result.toString())
    }
}
