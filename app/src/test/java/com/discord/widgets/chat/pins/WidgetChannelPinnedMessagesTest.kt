package com.discord.widgets.chat.pins

import com.discord.api.channel.Channel
import com.discord.api.user.User
import com.discord.models.message.Message
import com.discord.widgets.chat.list.entries.DividerEntry
import com.discord.widgets.chat.list.entries.EmptyPinsEntry
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.model.ChatListRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChannelPinnedMessagesTest {

    @Test fun distinguishesLoadingEmptyAndLoadedRows() {
        assertTrue(WidgetChannelPinnedMessages.rows(EMPTY_TEXT, state(), null).single() is LoadingEntry)

        val empty = WidgetChannelPinnedMessages.rows(EMPTY_TEXT, state(), emptyList()).single()
        assertTrue(empty is EmptyPinsEntry)
        assertEquals(EMPTY_TEXT, (empty as EmptyPinsEntry).text)

        val rows = WidgetChannelPinnedMessages.rows(EMPTY_TEXT, state(), listOf(message(2), message(1)))
        assertEquals(2, rows.count { it is MessageEntry })
        assertEquals(1, rows.count { it is DividerEntry })
    }

    /** Without a resolved channel there is nothing to render the rows against, so it stays loading. */
    @Test fun unresolvedChannelStaysLoading() {
        assertTrue(WidgetChannelPinnedMessages.rows(EMPTY_TEXT, null, listOf(message(1))).single() is LoadingEntry)
    }

    private fun state() = ChatListRows.State(channel())

    private fun message(id: Long) = Message(id, 1, author = User(id, "user$id"), content = "message$id")

    private fun channel() = Channel(
        topic = null, type = Channel.GUILD_TEXT, guildId = 1, name = "general", lastMessageId = 0,
        id = 1, ownerId = 0, recipientIds = null, recipients = null, position = 0,
        permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false,
        parentId = 0, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )

    private companion object {
        const val EMPTY_TEXT = "This channel doesn't have any pinned messages… yet."
    }
}
