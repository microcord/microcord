package com.discord.widgets.stage

import com.discord.api.channel.Channel
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.widgets.stage.model.AnalyticsDiscoverySource
import com.discord.widgets.stage.model.AnalyticsDiscoverySourceValues
import com.discord.widgets.stage.model.StageChannel
import com.discord.widgets.stage.settings.LiveStageNotificationBadgeTooltip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageExactPathContractsTest {
    @Test
    fun stageChannelPreservesDataAndActiveInstanceBehavior() {
        val channel = channel()
        val withoutInstance = StageChannel(
            channel = channel,
            participants = emptyList(),
            myRoles = StageRoles.AUDIENCE,
            speakerIds = emptySet(),
            speakers = emptyList(),
            audienceSize = 0,
            stageInstance = null,
            canAccess = true,
            containsMe = false,
        )

        assertFalse(withoutInstance.hasActiveStageInstance)
        assertEquals(withoutInstance, withoutInstance.copy())

        val active = withoutInstance.copy(
            stageInstance = StageInstance(
                id = 3L,
                guildId = channel.guildId,
                channelId = channel.id,
                topic = "Town hall",
                privacyLevel = StageInstancePrivacyLevel.PUBLIC,
                inviteCode = null,
            ),
            containsMe = true,
        )
        assertTrue(active.hasActiveStageInstance)
        assertTrue(active.containsMe)
        assertNotEquals(withoutInstance, active)
    }

    @Test
    fun exactFileFacadesAndTooltipIdentityAreRetained() {
        val source: AnalyticsDiscoverySource = AnalyticsDiscoverySourceValues.FEATURED

        assertEquals(6, source)
        assertEquals(
            "com.discord.widgets.stage.model.DiscoveryModelKt",
            Class.forName("com.discord.widgets.stage.model.DiscoveryModelKt").name,
        )
        assertEquals(
            "com.discord.widgets.stage.settings.LiveStageNotificationBadgeTooltipKt",
            Class.forName("com.discord.widgets.stage.settings.LiveStageNotificationBadgeTooltipKt").name,
        )
        assertEquals(
            "CACHE_KEY_LIVE_STAGE_NOTIFICATION_BADGE",
            LiveStageNotificationBadgeTooltip.cacheKey,
        )
        assertEquals("LIVE_STAGE_NOTIFICATION_BADGE", LiveStageNotificationBadgeTooltip.tooltipName)
    }

    private fun channel() = Channel(
        topic = null,
        type = Channel.GUILD_STAGE_VOICE,
        guildId = 1L,
        name = "Stage",
        lastMessageId = 0L,
        id = 2L,
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
