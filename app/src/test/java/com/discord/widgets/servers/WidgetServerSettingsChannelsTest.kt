package com.discord.widgets.servers

import com.discord.api.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetServerSettingsChannelsTest {
    @Test
    fun `sort filter keeps reference channel families`() {
        val channels = listOf(
            channel(1, Channel.GUILD_TEXT, 2),
            channel(2, Channel.GUILD_VOICE, 1),
            channel(3, Channel.CATEGORY, 0),
            channel(4, Channel.GUILD_ANNOUNCEMENT, 3),
            channel(5, Channel.GUILD_STAGE_VOICE, 4),
        ).associateBy(Channel::id)

        assertEquals(
            listOf(1L, 4L),
            WidgetServerSettingsChannels.Model.sortedGuildChannels(channels, Channel.GUILD_TEXT).keys.toList(),
        )
        assertEquals(
            listOf(2L),
            WidgetServerSettingsChannels.Model.sortedGuildChannels(channels, Channel.GUILD_VOICE).keys.toList(),
        )
        assertEquals(
            listOf(3L),
            WidgetServerSettingsChannels.Model.sortedGuildChannels(channels, Channel.CATEGORY).keys.toList(),
        )
    }

    @Test
    fun `drag compares flat indexes with server positions`() {
        val category = categoryItem(CATEGORY_A, 40)
        val text = channelItem(channel(TEXT_A, Channel.GUILD_TEXT, 90, CATEGORY_A))
        val rows = listOf(rootItem(), category, text)

        assertEquals(
            mapOf(
                CATEGORY_A to GuildChannelsSort.PositionUpdate(1, null),
                TEXT_A to GuildChannelsSort.PositionUpdate(2, null),
            ),
            GuildChannelsSort.changedPositions(rows, rows),
        )
    }

    @Test
    fun `cross-category drag reports displaced rows and explicit new parent`() {
        val original = listOf(
            rootItem(),
            categoryItem(CATEGORY_A, 10),
            channelItem(channel(TEXT_A, Channel.GUILD_TEXT, 20, CATEGORY_A)),
            channelItem(channel(VOICE_A, Channel.GUILD_VOICE, 30, CATEGORY_A)),
            categoryItem(CATEGORY_B, 40),
            channelItem(channel(TEXT_B, Channel.GUILD_TEXT, 50, CATEGORY_B)),
        )
        val reordered = listOf(original[0], original[1], original[3], original[4], original[5], original[2])

        assertEquals(
            mapOf(
                CATEGORY_A to GuildChannelsSort.PositionUpdate(1, null),
                VOICE_A to GuildChannelsSort.PositionUpdate(2, null),
                CATEGORY_B to GuildChannelsSort.PositionUpdate(3, null),
                TEXT_B to GuildChannelsSort.PositionUpdate(4, null),
                TEXT_A to GuildChannelsSort.PositionUpdate(5, CATEGORY_B),
            ),
            GuildChannelsSort.changedPositions(original, reordered),
        )
    }

    @Test
    fun `dragging a child to the root emits the explicit no-category marker`() {
        val original = listOf(
            rootItem(),
            categoryItem(CATEGORY_A, 10),
            channelItem(channel(TEXT_A, Channel.GUILD_TEXT, 20, CATEGORY_A)),
        )

        assertEquals(
            GuildChannelsSort.PositionUpdate(1, GuildChannelsSort.NO_CATEGORY),
            GuildChannelsSort.changedPositions(original, listOf(original[0], original[2], original[1]))[TEXT_A],
        )
    }

    @Test
    fun `moving category keeps its children under it in optimistic order`() {
        val channels = listOf(
            channel(CATEGORY_A, Channel.CATEGORY, 1),
            channel(TEXT_A, Channel.GUILD_TEXT, 0, CATEGORY_A),
            channel(CATEGORY_B, Channel.CATEGORY, 2),
            channel(TEXT_B, Channel.GUILD_TEXT, 0, CATEGORY_B),
        ).associateBy(Channel::id)
        val categoryRows = listOf(rootItem(), categoryItem(CATEGORY_A, 1), categoryItem(CATEGORY_B, 2))
        val updates = GuildChannelsSort.changedPositions(categoryRows, listOf(categoryRows[0], categoryRows[2], categoryRows[1]))
        val optimistic = GuildChannelsSort.applyPositions(channels, updates)
        val allRows = listOf(
            rootItem(),
            categoryItem(CATEGORY_A, optimistic.getValue(CATEGORY_A).position),
            channelItem(optimistic.getValue(TEXT_A)),
            categoryItem(CATEGORY_B, optimistic.getValue(CATEGORY_B).position),
            channelItem(optimistic.getValue(TEXT_B)),
        )

        assertEquals(
            listOf(0L, CATEGORY_B, TEXT_B, CATEGORY_A, TEXT_A),
            GuildChannelsSort.sortedRows(allRows).map { it.key.toLong() },
        )
    }

    @Test
    fun `text channels remain before voice channels inside every category`() {
        val rows = listOf(
            rootItem(),
            categoryItem(CATEGORY_A, 1),
            channelItem(channel(VOICE_A, Channel.GUILD_VOICE, 0, CATEGORY_A)),
            channelItem(channel(TEXT_A, Channel.GUILD_TEXT, 99, CATEGORY_A)),
        )

        assertEquals(
            listOf(0L, CATEGORY_A, TEXT_A, VOICE_A),
            GuildChannelsSort.sortedRows(rows).map { it.key.toLong() },
        )
    }

    @Test
    fun `old drag target stale old authoritative target never flickers`() {
        val baseline = mapOf(TEXT_A to channel(TEXT_A, Channel.GUILD_TEXT, 10, CATEGORY_A))
        val updates = mapOf(TEXT_A to GuildChannelsSort.PositionUpdate(3, CATEGORY_B))
        val state = ChannelOrderWriteState()
        val revision = state.begin(baseline, updates)
        val target = GuildChannelsSort.applyPositions(baseline, updates)

        assertSame(baseline, ChannelOrderWriteState().apply(baseline))
        assertOrder(target, state.apply(baseline))
        state.success(revision)
        assertOrder(target, state.apply(baseline))
        assertSame(target, state.apply(target))
        assertSame(baseline, state.apply(baseline))
    }

    @Test
    fun `stale store re-emission preserves the acknowledged channel order`() {
        val baseline = listOf(
            channel(TEXT_A, Channel.GUILD_TEXT, 1, CATEGORY_A),
            channel(TEXT_B, Channel.GUILD_TEXT, 2, CATEGORY_A),
        ).associateBy(Channel::id)
        val updates = mapOf(
            TEXT_A to GuildChannelsSort.PositionUpdate(2, null),
            TEXT_B to GuildChannelsSort.PositionUpdate(1, null),
        )
        val state = ChannelOrderWriteState()
        state.success(state.begin(baseline, updates))

        val reEmitted = state.apply(baseline)
        val rows = listOf(rootItem(), categoryItem(CATEGORY_A, 0)) + reEmitted.values.map(::channelItem)

        assertEquals(listOf(0L, CATEGORY_A, TEXT_B, TEXT_A), GuildChannelsSort.sortedRows(rows).map { it.key.toLong() })
    }

    @Test
    fun `overlapping drags keep newest target and rollback only matching failure`() {
        val baseline = mapOf(TEXT_A to channel(TEXT_A, Channel.GUILD_TEXT, 10, CATEGORY_A))
        val state = ChannelOrderWriteState()
        val first = state.begin(baseline, mapOf(TEXT_A to GuildChannelsSort.PositionUpdate(3, CATEGORY_B)))
        val firstTarget = state.apply(baseline)
        val second = state.begin(firstTarget, mapOf(TEXT_A to GuildChannelsSort.PositionUpdate(4, null)))

        assertFalse(state.failure(first))
        assertEquals(4, state.apply(baseline).getValue(TEXT_A).position)
        assertTrue(state.failure(second))
        assertSame(baseline, state.apply(baseline))
    }

    @Test
    fun `latest failure rolls back to preceding pending target`() {
        val baseline = mapOf(TEXT_A to channel(TEXT_A, Channel.GUILD_TEXT, 10, CATEGORY_A))
        val state = ChannelOrderWriteState()
        state.begin(baseline, mapOf(TEXT_A to GuildChannelsSort.PositionUpdate(3, CATEGORY_B)))
        val firstTarget = state.apply(baseline)
        val second = state.begin(firstTarget, mapOf(TEXT_A to GuildChannelsSort.PositionUpdate(4, null)))

        assertTrue(state.failure(second))
        assertOrder(firstTarget, state.apply(baseline))
    }

    @Test
    fun `newer failure exposes an older target already confirmed by the store`() {
        val baseline = mapOf(TEXT_A to channel(TEXT_A, Channel.GUILD_TEXT, 10, CATEGORY_A))
        val state = ChannelOrderWriteState()
        state.begin(baseline, mapOf(TEXT_A to GuildChannelsSort.PositionUpdate(3, CATEGORY_B)))
        val firstTarget = state.apply(baseline)
        val second = state.begin(firstTarget, mapOf(TEXT_A to GuildChannelsSort.PositionUpdate(4, null)))

        assertEquals(4, state.apply(firstTarget).getValue(TEXT_A).position)
        assertTrue(state.failure(second))
        assertSame(firstTarget, state.apply(firstTarget))
    }

    private fun assertOrder(expected: Map<Long, Channel>, actual: Map<Long, Channel>) {
        assertEquals(
            expected.mapValues { (_, channel) -> channel.position to channel.parentId },
            actual.mapValues { (_, channel) -> channel.position to channel.parentId },
        )
    }

    private fun rootItem() = categoryItem(0L, 0, canManageCategory = false)

    private fun categoryItem(id: Long, position: Int, canManageCategory: Boolean = true) =
        SettingsChannelListAdapter.CategoryItem(null, id, position, true, canManageCategory, true)

    private fun channelItem(channel: Channel) =
        SettingsChannelListAdapter.ChannelItem(channel, true, channel.parentId, true)

    private fun channel(id: Long, type: Int, position: Int, parentId: Long = 0L) = Channel(
        topic = null,
        type = type,
        guildId = 10L,
        name = "channel-$id",
        lastMessageId = 0L,
        id = id,
        ownerId = 0L,
        recipientIds = null,
        recipients = null,
        position = position,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0L,
        applicationId = 0L,
        nicks = null,
        nsfw = false,
        parentId = parentId,
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

    private companion object {
        const val CATEGORY_A = 9_223_372_036_854_775_000L
        const val CATEGORY_B = 9_223_372_036_854_775_001L
        const val TEXT_A = 9_223_372_036_854_775_002L
        const val TEXT_B = 9_223_372_036_854_775_003L
        const val VOICE_A = 9_223_372_036_854_775_004L
    }
}
