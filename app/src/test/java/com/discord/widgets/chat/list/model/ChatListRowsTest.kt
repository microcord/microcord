package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.message.Message
import com.discord.stores.StoreMessagesLoader
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListRowsTest {
    @Test fun groupsAtMostFiveMessagesWithinSevenMinutes() {
        val messages = (1L..6L).map { message(it, it * 60_000) }
        val rows = rows(messages)

        assertEquals(2, rows.count { it is ChatListEntry.Header })
        assertEquals(4, rows.count { it is ChatListEntry.Minimal })
        assertEquals(6, rows.mapNotNull(ChatListEntry::messageId).distinct().size)
    }

    @Test fun addsStableUnreadAndDayDividers() {
        val day = 86_400_000L
        val first = rows(listOf(message(1, day), message(2, day * 2)), unreadMessageId = 1)
        val second = rows(listOf(message(1, day), message(2, day * 2)), unreadMessageId = 1)

        assertEquals(2, first.count { it is ChatListEntry.DayDivider })
        assertEquals(1, first.count { it is ChatListEntry.UnreadDivider })
        assertEquals(first.map(ChatListEntry::stableId), second.map(ChatListEntry::stableId))
        assertEquals(first.size, first.map(ChatListEntry::stableId).distinct().size)
    }

    @Test fun rendersFailedReplyAttachmentsAndReactionsWithoutRichBinders() {
        val reply = ApiMessage(9, 1, User(2, "reply-author"), "quoted")
        val rich = message(10, 1000).copy(
            type = -2,
            content = "failed",
            referencedMessage = reply,
            attachments = listOf(MessageAttachment("https://cdn/file.bin", 4, 3, "https://proxy/file.bin", "file.bin", null, null)),
            reactions = listOf(MessageReaction(2, MessageReactionEmoji(null, null, false), false)),
        )
        val rows = rows(listOf(rich))

        assertTrue(rows.any { it is ChatListEntry.Failed && "Reply to reply-author" in it.text })
        assertTrue(rows.any { it is ChatListEntry.Attachment && it.attachment.filename == "file.bin" })
        assertTrue(rows.any { it is ChatListEntry.Reactions && it.reactions.single().count == 2 })
    }

    @Test fun emptyLoadedChannelGetsRealStartRowWhileDetachedGetsLoading() {
        assertTrue(rows(emptyList()).any { it is ChatListEntry.Start })
        assertTrue(rows(emptyList(), detached = true).any { it is ChatListEntry.Loading })
    }

    @Test fun adapterHasARecoveredLayoutForEveryBuiltRow() {
        rows(listOf(message(1, 1_000))).forEach { row ->
            assertTrue(WidgetChatListAdapter.layoutFor(WidgetChatListAdapter.viewType(row)) != 0)
        }
    }

    private fun rows(
        messages: List<Message>,
        unreadMessageId: Long? = null,
        detached: Boolean = false,
    ) = ChatListRows.build(ChatListRows.State(
        channel(),
        messages,
        StoreMessagesLoader.ChannelLoadedState(
            isInitialMessagesLoaded = true,
            isOldestMessagesLoaded = true,
        ),
        detached,
        unreadMessageId,
    ))

    private fun message(id: Long, timestamp: Long) = Message(
        id = id,
        channelId = 1,
        author = User(1, "author"),
        content = "message-$id",
        timestamp = UtcDateTime(timestamp),
        type = 0,
    )

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
