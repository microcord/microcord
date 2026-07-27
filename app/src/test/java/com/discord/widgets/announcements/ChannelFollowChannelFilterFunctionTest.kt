package com.discord.widgets.announcements

import com.discord.api.channel.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destination channel picker is filtered by two things at once: the set of channels the viewer
 * can manage webhooks in (computed once, across every guild), and the guild currently selected in
 * the picker above it.
 *
 * Dropping either half is a real bug — the id set alone would offer channels from other servers,
 * and the guild alone would offer channels the viewer cannot publish into.
 */
class ChannelFollowChannelFilterFunctionTest {

    private val filter = ChannelFollowChannelFilterFunction(
        selectedGuildId = 100L,
        channelIds = setOf(1L, 2L),
    )

    @Test
    fun `a permitted channel in the selected guild is included`() {
        assertTrue(filter.includeChannel(channel(id = 1L, guildId = 100L)))
    }

    @Test
    fun `a permitted channel in another guild is excluded`() {
        assertFalse(filter.includeChannel(channel(id = 2L, guildId = 200L)))
    }

    @Test
    fun `an unpermitted channel in the selected guild is excluded`() {
        assertFalse(filter.includeChannel(channel(id = 9L, guildId = 100L)))
    }

    /** Nothing selected yet: `guildId` 0 matches no real guild, so the list stays empty. */
    @Test
    fun `no selected guild yields nothing`() {
        val unselected = ChannelFollowChannelFilterFunction(selectedGuildId = 0L, channelIds = setOf(1L))
        assertFalse(unselected.includeChannel(channel(id = 1L, guildId = 100L)))
    }

    private fun channel(id: Long, guildId: Long, type: Int = Channel.GUILD_TEXT) = Channel(
        topic = null,
        type = type,
        guildId = guildId,
        name = "announcements",
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
