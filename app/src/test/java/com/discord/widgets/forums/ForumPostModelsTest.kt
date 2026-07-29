package com.discord.widgets.forums

import com.discord.api.channel.Channel
import com.discord.api.channel.ForumTag
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumPostModelsTest {
    @Test
    fun capsForumFooterCounts() {
        assertNull(messageCountText(null))
        assertEquals("49", messageCountText(49))
        assertEquals("50+", messageCountText(50))
        assertNull(unreadCountText(0))
        assertEquals("9", unreadCountText(9))
        assertEquals("10+", unreadCountText(10))
    }

    @Test
    fun classifiesAttachmentPreviewState() {
        val attachment = MessageAttachment(
            "https://cdn.example/image.BMP?x=1",
            1,
            2,
            "https://proxy.example/image.bmp",
            "SPOILER_image.bmp",
            16,
            16,
        )
        assertEquals(MessageAttachmentType.IMAGE, attachment.type)
        assertTrue(attachment.isSpoiler)
    }

    @Test
    fun browserOrdersMostRecentlyActivePostsFirst() {
        val older = ForumBrowserItem.PostItem(post(channel(1), 10))
        val newer = ForumBrowserItem.PostItem(post(channel(2), 20))

        assertEquals(listOf(newer, older), listOf(older, newer).sortedWith(ForumBrowserItem.postItemComparatorByMostRecent))
    }

    @Test
    fun readStateProjectionKeepsNeverSeenSeparateFromUnread() {
        val first = channel(1)
        val second = channel(2)
        val states = ForumPostReadStateManager.create(
            mapOf(first.id to first, second.id to second),
            persistedChannelIds = setOf(first.id),
            unreadChannelIds = setOf(first.id, second.id),
        )

        assertEquals(ForumPostReadState(1, hasSeenBefore = true, hasUnread = true), states[1])
        assertEquals(ForumPostReadState(2, hasSeenBefore = false, hasUnread = true), states[2])
    }

    @Test
    fun tagProjectionUsesFirstKnownAppliedTag() {
        val first = ForumTag(1, "Help", null, "question")
        val second = ForumTag(2, "News", null, "newspaper")
        val parent = channel(10).copy(availableTags = listOf(first, second))
        val post = channel(11).copy(appliedTags = listOf(99, 2, 1))

        assertEquals("News", resolvePostTag(parent, post, emptyMap())?.tagName)
    }

    private fun post(channel: Channel, timestamp: Long) = PostData(
        channel, null, 0, emptyMap(), emptyMap(), emptyMap(), null, false, timestamp, null,
        null, null, false, ForumPostReadState.createDefault(channel.id), null, emptyList(),
    )

    private fun channel(id: Long) = Channel(
        topic = null,
        type = Channel.PUBLIC_THREAD,
        guildId = 1,
        name = "post-$id",
        lastMessageId = 0,
        id = id,
        ownerId = 0,
        recipientIds = null,
        recipients = null,
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0,
        applicationId = 0,
        nicks = null,
        nsfw = false,
        parentId = 0,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = null,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = null,
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )
}
