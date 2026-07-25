package com.discord.widgets.channels.list

import com.discord.api.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChannelListRowsTest {
    @Test fun ordersUncategorizedThenCategoriesAndChildren() {
        val channels = listOf(
            channel(30, Channel.CATEGORY, 2, name = "later"),
            channel(31, Channel.GUILD_TEXT, 2, parentId = 30),
            channel(20, Channel.CATEGORY, 1, name = "first"),
            channel(22, Channel.GUILD_TEXT, 2, parentId = 20),
            channel(21, Channel.GUILD_TEXT, 1, parentId = 20),
            channel(10, Channel.GUILD_TEXT, 9),
        )

        assertEquals(listOf(20L, 21L, 22L, 30L, 31L, 10L), ChannelListRows.guild(channels, viewAll(channels)).map { it.stableId })
    }

    @Test fun filtersPermissionsAndVoiceConnectAndDropsEmptyCategories() {
        val channels = listOf(
            channel(1, Channel.CATEGORY, 0),
            channel(2, Channel.GUILD_TEXT, 0, parentId = 1),
            channel(3, Channel.GUILD_VOICE, 1),
            channel(4, Channel.GUILD_STAGE_VOICE, 2),
            channel(5, Channel.DIRECTORY, 3),
        )
        val permissions = mapOf(
            2L to 0L,
            3L to ChannelListRows.VIEW_CHANNEL,
            4L to (ChannelListRows.VIEW_CHANNEL or ChannelListRows.CONNECT),
            5L to ChannelListRows.VIEW_CHANNEL,
        )

        val rows = ChannelListRows.guild(channels, permissions)
        assertEquals(listOf(3L, 4L, 5L), rows.map { it.stableId })
        assertFalse((rows[0] as ChannelListRow.Voice).canConnect)
    }

    @Test fun sortsPrivateChannelsByLastMessageThenId() {
        val channels = listOf(
            channel(3, Channel.DM, lastMessageId = 5),
            channel(2, Channel.GROUP_DM, lastMessageId = 7),
            channel(1, Channel.DM, lastMessageId = 7),
        )

        assertEquals(listOf(1L, 2L, 3L), ChannelListRows.private(channels).map { it.stableId })
    }

    @Test fun projectsReadStateAndKeepsSelectedChannelVisible() {
        val channels = listOf(
            channel(1, Channel.CATEGORY),
            channel(2, Channel.GUILD_TEXT, parentId = 1),
            channel(3, Channel.PUBLIC_THREAD, parentId = 2),
        )
        val rows = ChannelListRows.guild(
            channels,
            permissions = mapOf(2L to ChannelListRows.VIEW_CHANNEL, 3L to 0L),
            unreadChannelIds = setOf(2, 3),
            mentionCounts = mapOf(2L to 1, 3L to 2),
            selectedChannelId = 3,
        )

        assertEquals(listOf(1L, 2L), rows.map(ChannelListRow::stableId))
        assertEquals(ChannelListRow.Category(channels[0], unread = true, mentionCount = 1), rows[0])
        assertEquals(ChannelListRow.Text(channels[1], unread = true, mentionCount = 1), rows[1])
    }

    @Test fun collapsedCategoryKeepsOnlyImportantChildren() {
        val channels = listOf(
            channel(1, Channel.CATEGORY),
            channel(2, Channel.GUILD_TEXT, parentId = 1),
            channel(3, Channel.GUILD_TEXT, parentId = 1),
        )

        val rows = ChannelListRows.guild(
            channels,
            viewAll(channels),
            unreadChannelIds = setOf(3),
            selectedChannelId = 2,
            collapsedCategoryIds = setOf(1),
        )

        assertEquals(listOf(1L, 2L, 3L), rows.map(ChannelListRow::stableId))
        assertEquals(emptyList<Long>(), ChannelListRows.guild(
            channels.take(1), viewAll(channels), collapsedCategoryIds = emptySet(),
        ).map(ChannelListRow::stableId))
    }

    private fun viewAll(channels: List<Channel>) = channels.associate { it.id to (ChannelListRows.VIEW_CHANNEL or ChannelListRows.CONNECT) }

    private fun channel(
        id: Long,
        type: Int,
        position: Int = 0,
        parentId: Long = 0,
        name: String = "channel-$id",
        lastMessageId: Long = 0,
    ) = Channel(
        topic = null, type = type, guildId = 1, name = name, lastMessageId = lastMessageId,
        id = id, ownerId = 0, recipientIds = null, recipients = null, position = position,
        permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false,
        parentId = parentId, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )
}
