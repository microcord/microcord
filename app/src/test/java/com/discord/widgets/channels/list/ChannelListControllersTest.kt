package com.discord.widgets.channels.list

import com.discord.utilities.threads.ThreadUtils
import com.discord.api.channel.Channel
import com.discord.api.thread.ThreadMetadata
import com.discord.utilities.SnowflakeUtils
import com.discord.widgets.channels.list.items.ChannelListBottomNavSpaceItem
import com.discord.widgets.channels.list.items.ChannelListItemAddServer
import com.discord.widgets.channels.list.items.ChannelListItemEventsSeparator
import com.discord.widgets.channels.list.items.ChannelListItemMfaNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelListControllersTest {

    @Test
    fun `mentions win and visible unreads suppress offscreen unread bars`() {
        val items = listOf(
            unread(mentionCount = 0, isUnread = true),
            unread(mentionCount = 0, isUnread = true),
            unread(mentionCount = 2, isUnread = false),
        )

        val model = WidgetChannelListUnreads.Model.get(1..1, items)

        assertNull(model.topIndicator)
        assertEquals(
            WidgetChannelListUnreads.Model.Indicator(
                WidgetChannelListUnreads.Model.TYPE_MENTION,
                2,
            ),
            model.bottomIndicator,
        )
    }

    @Test
    fun `dataset shrink drops unread bars for the stale frame`() {
        assertEquals(
            WidgetChannelListUnreads.Model(),
            WidgetChannelListUnreads.Model.get(0..10, listOf(unread(0, true))),
        )
    }

    @Test
    fun `thread auto archive never predates thread creation`() {
        val createdAt = 1_700_000_000_000L
        val channel = channel(
            id = SnowflakeUtils.fromTimestamp(createdAt),
            metadata = ThreadMetadata(
                archived = false,
                archiveTimestamp = "2020-01-01T00:00:00.000Z",
                autoArchiveDuration = 60,
                locked = false,
            ),
        )

        assertEquals(
            createdAt + 60 * 60_000L,
            ThreadUtils.computeThreadAutoArchiveTimeMs(channel),
        )
    }

    @Test
    fun `non data rows keep reference identity equality`() {
        assertFalse(ChannelListBottomNavSpaceItem() == ChannelListBottomNavSpaceItem())
        assertFalse(ChannelListItemAddServer() == ChannelListItemAddServer())
        assertFalse(ChannelListItemEventsSeparator(true) == ChannelListItemEventsSeparator(true))
        assertFalse(ChannelListItemMfaNotice() == ChannelListItemMfaNotice())
    }

    private fun unread(mentionCount: Int, isUnread: Boolean) =
        object : WidgetChannelListUnreads.UnreadItem {
            override val mentionCount = mentionCount
            override val isUnread = isUnread
        }

    private fun channel(id: Long, metadata: ThreadMetadata) = Channel(
        topic = null,
        type = Channel.PUBLIC_THREAD,
        guildId = 1L,
        name = "thread",
        lastMessageId = 0L,
        id = id,
        ownerId = 0L,
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
        parentId = 2L,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = null,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = metadata,
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )
}
