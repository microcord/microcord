package com.discord.widgets.servers

import com.discord.api.channel.Channel
import org.junit.Assert.assertEquals
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

    private fun channel(id: Long, type: Int, position: Int) = Channel(
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
        parentId = 0L,
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
