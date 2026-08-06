package com.discord.widgets.forums

import com.discord.api.channel.Channel
import com.discord.api.channel.ForumTag
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.permission.Permission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun creatingPostRequiresSendAndPublicThreadPermissions() {
        assertTrue(ForumUtils.canCreatePost(Permission.SEND_MESSAGES or Permission.CREATE_PUBLIC_THREADS))
        assertFalse(ForumUtils.canCreatePost(Permission.SEND_MESSAGES))
        assertFalse(ForumUtils.canCreatePost(Permission.CREATE_PUBLIC_THREADS))
    }

    @Test
    fun browserOrdersMostRecentlyActivePostsFirst() {
        val older = ForumBrowserItem.PostItem(post(channel(1), 10))
        val newer = ForumBrowserItem.PostItem(post(channel(2), 20))

        assertEquals(listOf(newer, older), listOf(older, newer).sortedWith(ForumBrowserItem.postItemComparatorByMostRecent))
    }

    @Test
    fun browserCanSortPostsByCreationDate() {
        val olderIdWithNewActivity = ForumBrowserItem.PostItem(post(channel(1), 20))
        val newerIdWithOldActivity = ForumBrowserItem.PostItem(post(channel(2), 10))
        val items = listOf(olderIdWithNewActivity, newerIdWithOldActivity)

        assertEquals(
            listOf(newerIdWithOldActivity, olderIdWithNewActivity),
            sortForumItems(items, ForumSortOrder.CREATION_DATE),
        )
        assertEquals(items, sortForumItems(items, ForumSortOrder.LATEST_ACTIVITY))
    }

    @Test
    fun mediaGridUsesWideSingleAndLargeLeftTripleLayouts() {
        assertEquals(listOf(MediaGridRect(0, 0, 320, 180)), mediaGridRects(1, 320, 180, 2))
        assertEquals(
            listOf(
                MediaGridRect(0, 0, 159, 180),
                MediaGridRect(161, 0, 159, 89),
                MediaGridRect(161, 91, 159, 89),
            ),
            mediaGridRects(3, 320, 180, 2),
        )
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

    /** Unknown ids are dropped, applied order is kept, and anything past the cap becomes overflow. */
    @Test
    fun tagProjectionKeepsTwoKnownTagsAndCountsTheRest() {
        val tags = (1L..4L).map { ForumTag(it, "Tag$it", null, "emoji$it") }
        val parent = channel(10).copy(availableTags = tags)
        val post = channel(11).copy(appliedTags = listOf(99, 2, 1, 4))

        val projected = resolvePostTags(parent, post, emptyMap())

        assertEquals(listOf("Tag2", "Tag1"), projected.visible.map { it.tagName })
        assertEquals(1, projected.overflow)
    }

    private fun post(channel: Channel, timestamp: Long) = PostData(
        channel, null, 0, emptyMap(), emptyMap(), emptyMap(), null, false, timestamp, null,
        PostTagsData(), null, false, ForumPostReadState.createDefault(channel.id), null, emptyList(),
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
