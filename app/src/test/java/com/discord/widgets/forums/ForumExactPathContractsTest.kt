package com.discord.widgets.forums

import com.discord.api.channel.Channel
import com.discord.api.channel.ForumTag
import com.discord.api.thread.ThreadMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumExactPathContractsTest {
    @Test
    fun postDerivedFieldsMatch12621() {
        val active = post(channel(flags = Channel.FLAG_PINNED), ownerId = 7, myUserId = 7)
        val archived = post(channel(archived = true), ownerId = 8, myUserId = 7)

        assertTrue(active.isActivePost)
        assertTrue(active.isPinned)
        assertTrue(active.isMe)
        assertFalse(archived.isActivePost)
        assertFalse(archived.isMe)
    }

    @Test
    fun tagAndDefaultReadStateMatch12621() {
        val tag = PostTagData.fromTag(ForumTag(1, "Help", 2, "question"), animated = true)

        assertEquals("Help", tag.tagName)
        assertEquals("2", tag.messageReactionEmoji.id)
        assertTrue(tag.messageReactionEmoji.animated)
        assertEquals(ForumPostReadState(9, hasSeenBefore = true, hasUnread = false), ForumPostReadState.createDefault(9))
    }

    private fun post(channel: Channel, ownerId: Long, myUserId: Long) = PostData(
        channel = channel,
        owner = com.discord.models.user.CoreUser(id = ownerId, username = "owner", discriminator = 1),
        myUserId = myUserId,
        guildMembers = emptyMap(),
        guildRoles = emptyMap(),
        channelNames = emptyMap(),
        firstMessage = null,
        isFirstMessageBlocked = false,
        mostRecentMessageTimestamp = null,
        messageCount = null,
        tag = null,
        mostCommonReaction = null,
        isDefaultReaction = false,
        readState = ForumPostReadState.createDefault(channel.id),
        unreadCount = null,
        typingUsers = emptyList(),
    )

    private fun channel(flags: Long? = null, archived: Boolean = false) = Channel(
        topic = null,
        type = Channel.PUBLIC_THREAD,
        guildId = 1L,
        name = "post",
        lastMessageId = 0L,
        id = 1,
        ownerId = 7L,
        recipientIds = null,
        recipients = null,
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0L,
        applicationId = 0L,
        nicks = null,
        nsfw = false,
        parentId = 0L,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = flags,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = ThreadMetadata(archived, null, 1440, false),
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )
}
