package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.api.message.MessageReference
import com.discord.api.message.MessageFlags
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.stores.StoreMessageReplies
import com.discord.stores.StoreStream
import com.discord.stores.storeTestScheduler
import com.discord.utilities.feature.ClientPreferenceSnapshotTest.MemoryPreferences
import com.discord.widgets.chat.list.entries.BlockedMessagesEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.discord.widgets.chat.list.entries.NewMessagesEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.entries.TimestampEntry
import com.discord.widgets.chat.list.entries.InviteEmbedResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WidgetChatListModelMessagesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun setUp() {
        val preferences = MemoryPreferences()
        StoreStream.initializeForTesting(temporary.newFolder(), preferences, preferences,
            storeTestScheduler("chat-list-model-test"))
    }

    @Test fun forwardedContentIsHostedBelowTheAuthorInsteadOfAfterTheAvatarRow() {
        val forwarded = message(3L, 3_000L).copy(content = "", flags = MessageFlags.HAS_SNAPSHOT,
            messageReference = MessageReference(2L, 20L, 1L, type = 1))
        val model = WidgetChatListModelMessages.build(channel(), metadata(listOf(forwarded)),
            inviteResolver = InviteEmbedResolver.UNRESOLVED)
        assertTrue(model.items.none { it is ForwardedMessageEntry })
        val row = model.items.filterIsInstance<MessageEntry>().single()
        assertEquals(forwarded.id, row.accessories.filterIsInstance<ForwardedMessageEntry>().single().parentMessage.id)
    }

    @Test fun missingReplyStateBecomesUnloadedAndCoreIsFoundAfterReordering() {
        val reply = Message(
            id = 2L,
            channelId = 5L,
            type = 19,
            messageReference = MessageReference(null, 5L, 9L),
        )
        assertSame(
            StoreMessageReplies.MessageState.Unloaded,
            WidgetChatListModelMessages.resolveReplyState(reply, emptyMap()),
        )

        val target = message(9L, 1_000L)
        val core = MessageEntry(target, null, null, roles = null, nickOrUsernames = emptyMap(),
            isExpandedBlocked = false, permissionsForChannel = null)
        val reactions = ReactionsEntry(target, true, true, true)
        assertSame(core, WidgetChatListModelMessages.findMessageEntry(listOf(reactions, core)))
    }

    @Test fun groupsBlockedRunsAndKeepsExpandedRows() {
        val metadata = metadata((1L..3L).map { message(it, it * 1_000) })

        val collapsed = WidgetChatListModelMessages.build(
            channel(), metadata, blockedAuthorIds = setOf(7L),
            inviteResolver = InviteEmbedResolver.UNRESOLVED,
        )
        assertEquals(3, collapsed.items.filterIsInstance<BlockedMessagesEntry>().single().blockedCount)
        assertTrue(collapsed.items.none { it is MessageEntry })

        val expanded = WidgetChatListModelMessages.build(
            channel(), metadata,
            blockedAuthorIds = setOf(7L),
            expandedBlockedMessageIds = listOf(1L),
            inviteResolver = InviteEmbedResolver.UNRESOLVED,
        )
        assertEquals(3, expanded.items.filterIsInstance<MessageEntry>().size)
        assertTrue(expanded.items.filterIsInstance<MessageEntry>().all { it.isInExpandedBlockedMessageChunk() })
    }

    @Test fun appliesReferenceConcatUnreadAndTimestampRules() {
        val metadata = metadata(listOf(message(1, 1_000), message(2, 2_000), message(3, 500_000)))
        val model = WidgetChatListModelMessages.build(
            channel(), metadata, newMessagesMarkerMessageId = 2L,
            inviteResolver = InviteEmbedResolver.UNRESOLVED,
        )
        val messages = model.items.filterIsInstance<MessageEntry>()

        assertEquals(3L, model.newestKnownMessageId)
        assertEquals(1L, model.oldestMessageId)
        assertTrue(messages.single { it.message.id == 2L }.type == 1)
        assertFalse(messages.single { it.message.id == 3L }.type == 1)
        assertEquals(1, model.items.count { it is NewMessagesEntry })
        assertEquals(1, model.items.count { it is TimestampEntry })
    }

    @Test fun fetchedMentionNamesAndPendingStateChangeTheRowNameMap() {
        val names = mutableMapOf<Long, String>()

        WidgetChatListModelMessages.mergeFetchedMentionNames(
            names,
            "<@8> <@!9>",
            mapOf(8L to CoreUser(id = 8, username = "resolved", globalName = "Resolved User")),
            setOf(9L),
        )

        assertEquals(mapOf(8L to "Resolved User", 9L to "fetching"), names)
    }

    private fun metadata(messages: List<Message>) = WidgetChatListModelMessages.MessagesWithMetadata(
        messages, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
    )

    private fun message(id: Long, timestamp: Long) = Message(
        id = id,
        channelId = 10,
        author = User(7, "author"),
        content = "message-$id",
        timestamp = UtcDateTime(timestamp),
        type = 0,
    )

    private fun channel() = Channel(
        topic = null, type = Channel.GUILD_TEXT, guildId = 1, name = "general", lastMessageId = 0,
        id = 10, ownerId = 0, recipientIds = null, recipients = null, position = 0,
        permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false,
        parentId = 0, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )
}
