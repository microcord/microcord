package com.discord.widgets.chat.pins

import com.discord.api.channel.Channel
import com.discord.api.user.User
import com.discord.models.message.Message
import com.discord.widgets.chat.list.entries.ChatListEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChannelPinnedMessagesTest {
    @Test fun distinguishesLoadingEmptyAndLoadedRows() {
        assertTrue(WidgetChannelPinnedMessages.rows(1, channel(), null).single() is ChatListEntry.Loading)
        assertTrue(WidgetChannelPinnedMessages.rows(1, channel(), emptyList()).single() is ChatListEntry.EmptyPins)

        val rows = WidgetChannelPinnedMessages.rows(1, channel(), listOf(message(2), message(1)))
        assertEquals(2, rows.count { it is ChatListEntry.Header })
        assertEquals(1, rows.count { it is ChatListEntry.Divider })
    }

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
}
