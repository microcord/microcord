package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.api.user.User
import com.discord.stores.StoreMessagesLoader
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.entries.SpacerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChatListModelTest {
    @Test fun detachedStateAndChannelRowsMatchReference() {
        assertEquals(
            WidgetChatListModel.ChatListState.DETACHED_UNTOUCHED,
            WidgetChatListModel.ChatListState.from(StoreMessagesLoader.ChannelLoadedState()),
        )
        assertEquals(
            WidgetChatListModel.ChatListState.DETACHED,
            WidgetChatListModel.ChatListState.from(
                StoreMessagesLoader.ChannelLoadedState(isTouchedSinceLastJump = true),
            ),
        )

        val model = WidgetChatListModel.buildChannelModel(
            channel = channel(Channel.GUILD_TEXT),
            top = WidgetChatListModelTop(SpacerEntry(10)),
            messages = WidgetChatListModelMessages(emptyList(), 1, 2, null, 3, null),
            loadingState = StoreMessagesLoader.ChannelLoadedState(isLoadingMessages = true),
            channelNames = mapOf(10L to "general"),
            myId = 7,
            computedMembers = emptyMap(),
            guild = null,
            relationships = emptyMap(),
            chatListState = WidgetChatListModel.ChatListState.DETACHED,
        )

        assertTrue(model.list.first() is LoadingEntry)
        assertEquals(1L, model.oldestMessageId)
        assertEquals(2L, model.newestKnownMessageId)
        assertEquals(3L, model.newMessagesMarkerMessageId)
        assertTrue(model.isLoadingMessages)
        assertTrue(model.isSpoilerClickAllowed)
    }

    @Test fun stickerGreetRequiresEligibleUnblockedDm() {
        val recipient = User(9, "recipient")
        val dm = channel(Channel.DM, listOf(recipient))
        val loaded = StoreMessagesLoader.ChannelLoadedState(
            isInitialMessagesLoaded = true,
            isOldestMessagesLoaded = true,
        )
        val messages = WidgetChatListModelMessages(emptyList(), 0, 0, null, 0, null)

        assertTrue(WidgetChatListModel.shouldShowStickerGreet(loaded, messages, dm, emptyMap()))
        assertFalse(WidgetChatListModel.shouldShowStickerGreet(loaded, messages, dm, mapOf(9L to 2)))
        assertFalse(
            WidgetChatListModel.shouldShowStickerGreet(
                loaded,
                messages,
                channel(Channel.DM, listOf(recipient.copy(system = true))),
                emptyMap(),
            ),
        )
    }

    private fun channel(type: Int, recipients: List<User>? = null) = Channel(
        topic = null, type = type, guildId = 1, name = "general", lastMessageId = 0,
        id = 10, ownerId = 0, recipientIds = recipients?.map(User::id), recipients = recipients,
        position = 0, permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false, parentId = 0,
        memberListId = null, rateLimitPerUser = 0, defaultAutoArchiveDuration = null,
        rtcRegion = null, flags = null, guildHashes = null, availableTags = null,
        appliedTags = null, threadMetadata = null, messageCount = null, memberCount = null,
        memberIdsPreview = null, member = null,
    )
}
