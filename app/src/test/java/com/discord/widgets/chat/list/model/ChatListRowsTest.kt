package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.MessageSnapshot
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.permission.Permission
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.message.Message
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.stores.StoreMessagesLoader
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.discord.widgets.chat.list.entries.NewMessagesEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.entries.SpacerEntry
import com.discord.widgets.chat.list.entries.StartOfChatEntry
import com.discord.widgets.chat.list.entries.StartOfPrivateChatEntry
import com.discord.widgets.chat.list.entries.StickerEntry
import com.discord.widgets.chat.list.entries.TimestampEntry
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListRowsTest {

    @Test fun messageEntryIncludesItsAuthorDisplayName() {
        val body = rows(listOf(message(1, 1_000))).filterIsInstance<MessageEntry>().single()

        assertEquals("Author Display", body.nickOrUsernames[1L])
    }

    @Test fun directMessageStartRowUsesRecipientAvatar() {
        val directMessage = channel().copy(
            type = Channel.DM,
            guildId = 0L,
            name = null,
            recipients = listOf(User(8L, "recipient")),
        )

        val start = rows(emptyList(), channel = directMessage).filterIsInstance<StartOfPrivateChatEntry>().single()

        assertTrue(start.channelIcon?.isNotBlank() == true)
    }

    @Test fun groupsAtMostFiveMessagesWithinSevenMinutes() {
        val messages = (1L..6L).map { message(it, it * 60_000) }
        val bodies = rows(messages).filterIsInstance<MessageEntry>()

        assertEquals(2, bodies.count { it.type == ChatListEntry.MESSAGE })
        assertEquals(4, bodies.count { it.type == ChatListEntry.MESSAGE_MINIMAL })
        assertEquals(6, bodies.map { it.message.id }.distinct().size)
    }

    @Test fun addsStableUnreadAndDayDividers() {
        val day = 86_400_000L
        val first = rows(listOf(message(1, day), message(2, day * 2)), unreadMessageId = 1)
        val second = rows(listOf(message(1, day), message(2, day * 2)), unreadMessageId = 1)

        assertEquals(2, first.count { it is TimestampEntry })
        assertEquals(1, first.count { it is NewMessagesEntry })
        assertEquals(first.map(ChatListEntry::key), second.map(ChatListEntry::key))
        assertEquals(first.size, first.map(ChatListEntry::key).distinct().size)
    }

    @Test fun replyUsesDedicatedRowTypeAndKeepsOriginalContent() {
        val reply = ApiMessage(9, 1, User(2, "reply-author"), "quoted")
        val rich = message(10, 1000).copy(
            type = 19,
            content = "reply body",
            messageReference = com.discord.api.message.MessageReference(1, 1, 9),
            referencedMessage = reply,
            attachments = listOf(
                MessageAttachment("https://cdn/file.bin", 4, 3, "https://proxy/file.bin", "file.bin", null, null),
            ),
            // A reaction emoji always carries either an id (custom) or a name (unicode); the
            // reference derives its map key as `id ?: name!!`, so a fixture with both null is not a
            // representable reaction. See MessageReactionEmoji.reactionKey.
            reactions = listOf(MessageReaction(2, MessageReactionEmoji(null, "🎉", false), false)),
        )
        val rows = rows(listOf(rich))

        val body = rows.filterIsInstance<MessageEntry>().single()
        assertEquals(ChatListEntry.REPLY, body.type)
        assertEquals("reply body", body.message.content)
        assertTrue(rows.any { it is AttachmentEntry && it.attachment.filename == "file.bin" })
        assertEquals(
            2,
            rows.filterIsInstance<ReactionsEntry>().single().message.reactions?.single()?.count,
        )
    }

    @Test fun emptyLoadedChannelGetsRealStartRowWhileDetachedGetsLoading() {
        assertTrue(rows(emptyList()).any { it is StartOfChatEntry })
        assertTrue(rows(emptyList(), detached = true).any { it is LoadingEntry })
    }

    @Test fun reactionRowCarriesReferencePermissions() {
        val reaction = MessageReaction(2, MessageReactionEmoji(null, "👍", false), true)
        val withPermission = rows(
            listOf(message(1, 1_000).copy(reactions = listOf(reaction))),
            permissions = Permission.ADD_REACTIONS,
        ).filterIsInstance<ReactionsEntry>().single()

        assertTrue(withPermission.canAddReactions)
        assertTrue(withPermission.canCreateReactions)

        val withoutPermission = rows(listOf(message(1, 1_000).copy(reactions = listOf(reaction))))
            .filterIsInstance<ReactionsEntry>().single()
        assertTrue(withoutPermission.canAddReactions)
        assertFalse(withoutPermission.canCreateReactions)
    }

    @Test fun liveShapedForwardKeepsParentHeaderAndFlattensSnapshotContentAndMedia() {
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
        val api = requireNotNull(javaClass.getResourceAsStream("/fixtures/forwarded_message_live.json"))
            .reader().use { gson.fromJson(it, ApiMessage::class.java) }
        val forwarded = Message(api)
        val state = ChatListRows.State(channel())
        val rows = rows(listOf(forwarded))
        val parent = rows.filterIsInstance<MessageEntry>().single()
        val entry = rows
            .filterIsInstance<ForwardedMessageEntry>().single()

        assertEquals(ChatListEntry.MESSAGE, parent.type)
        assertEquals("parent context", parent.message.content)
        assertEquals(ForwardedMessageEntry.State.CONTENT, entry.state)
        assertEquals("forwarded body", entry.message!!.content)
        assertTrue(entry.rows.none { it is MessageEntry || it is ForwardedMessageEntry })
        assertEquals(
            listOf(AttachmentEntry::class, EmbedEntry::class, StickerEntry::class),
            entry.rows.map { it::class },
        )
        assertFalse(entry.message!!.components.isNullOrEmpty())
        assertEquals("Sanitized question", entry.message!!.poll!!.question.text)

        val snapshot = api.messageSnapshots!!.single().message
        val mediaOnly = forwarded.copy(
            messageSnapshots = listOf(MessageSnapshot(snapshot.copy(content = null))),
        )
        val mediaOnlyEntry = ChatListRows.forwardedRows(state, mediaOnly).single()
        assertTrue(mediaOnlyEntry.message!!.content.isNullOrEmpty())
        assertTrue(mediaOnlyEntry.rows.any { it is AttachmentEntry })
        assertTrue(mediaOnlyEntry.rows.none { it is MessageEntry })

        val system = forwarded.copy(
            messageSnapshots = listOf(MessageSnapshot(snapshot.copy(type = 7))),
        )
        val systemEntry = ChatListRows.forwardedRows(state, system).single()
        assertEquals(7, systemEntry.message!!.type)
        assertTrue(systemEntry.rows.none { it is MessageEntry })
    }

    /** The list is rendered by a reversed layout manager, so row 0 is the bottom of the screen. */
    @Test fun spacerIsTheFirstRowSoTheNewestMessageClearsTheChatInput() {
        assertTrue(rows(listOf(message(1, 1_000))).first() is SpacerEntry)
    }

    private fun rows(
        messages: List<Message>,
        unreadMessageId: Long? = null,
        detached: Boolean = false,
        permissions: Long? = null,
        channel: Channel = channel(),
    ) = ChatListRows.build(
        ChatListRows.State(
            channel = channel,
            messages = messages,
            loaded = StoreMessagesLoader.ChannelLoadedState(
                isInitialMessagesLoaded = true,
                isOldestMessagesLoaded = true,
            ),
            detached = detached,
            unreadMessageId = unreadMessageId,
            permissions = permissions,
        ),
    )

    private fun message(id: Long, timestamp: Long) = Message(
        id = id,
        channelId = 1,
        author = User(1, "author", globalName = "Author Display"),
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
