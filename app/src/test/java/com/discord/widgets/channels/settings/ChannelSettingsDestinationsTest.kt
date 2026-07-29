package com.discord.widgets.channels.settings

import com.discord.api.channel.Channel
import com.discord.api.guild.GuildFeature
import com.discord.models.guild.Guild
import com.discord.widgets.voice.settings.WidgetVoiceChannelSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSettingsDestinationsTest {

    @Test
    fun slowModeProgressRoundsUpToSupportedValue() {
        assertEquals(0, WidgetTextChannelSettings.slowModeProgress(0))
        assertEquals(2, WidgetTextChannelSettings.slowModeProgress(6))
        assertEquals(13, WidgetTextChannelSettings.slowModeProgress(Int.MAX_VALUE))
    }

    @Test
    fun bitrateLimitTracksBoostTierAndStageCap() {
        val voice = channel(Channel.GUILD_VOICE)
        assertEquals(96, WidgetVoiceChannelSettings.maxBitrateKbps(Guild(), voice))
        assertEquals(256, WidgetVoiceChannelSettings.maxBitrateKbps(Guild(premiumTier = 2), voice))
        assertEquals(
            384,
            WidgetVoiceChannelSettings.maxBitrateKbps(
                Guild(features = setOf(GuildFeature.VIP_REGIONS)),
                voice,
            ),
        )
        assertEquals(
            WidgetVoiceChannelSettings.DEFAULT_BITRATE_KBPS,
            WidgetVoiceChannelSettings.maxBitrateKbps(Guild(premiumTier = 3), channel(Channel.GUILD_STAGE_VOICE)),
        )
    }

    private fun channel(type: Int) = Channel(
        topic = null,
        type = type,
        guildId = 1,
        name = "voice",
        lastMessageId = 0,
        id = 2,
        ownerId = 0,
        recipientIds = null,
        recipients = null,
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 64_000,
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
