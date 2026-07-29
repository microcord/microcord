package com.discord.widgets.chat.list.reactions

import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.stores.StoreMessageReactions
import org.junit.Assert.assertEquals
import org.junit.Test

class ManageReactionsModelTest {
    private val emoji = MessageReactionEmoji(null, "\uD83D\uDE00", false)

    @Test
    fun loadingAndFailureMapToStableRows() {
        assertEquals(listOf(ResultItem.Loading), ResultItem.from(
            StoreMessageReactions.EmojiResults.Loading,
            canManageMessages = false,
            myId = 1L,
            guildMembers = emptyMap(),
        ))

        val rows = ResultItem.from(
            StoreMessageReactions.EmojiResults.Failure(2L, 3L, emoji),
            canManageMessages = false,
            myId = 1L,
            guildMembers = emptyMap(),
        )
        assertEquals(listOf(ResultItem.Error(2L, 3L, emoji)), rows)
    }
}
