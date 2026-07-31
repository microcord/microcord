package com.discord.widgets.stage

import com.discord.api.channel.Channel
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.stage.model.AnalyticsDiscoverySource
import com.discord.widgets.stage.model.AnalyticsDiscoverySourceValues
import com.discord.widgets.stage.model.StageChannel
import com.discord.widgets.stage.settings.LiveStageNotificationBadgeTooltip
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Observable

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
        val paths = listOf(
            "widgets/stage/model/DiscoveryModelKt.kt",
            "widgets/stage/settings/LiveStageNotificationBadgeTooltipKt.kt",
        )
        val source: AnalyticsDiscoverySource = AnalyticsDiscoverySourceValues.FEATURED

        assertEquals(paths, paths.filter { File("src/main/java/com/discord/$it").isFile })
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

    @Test
    fun suppressingSelfUsesTheNullSerializingVoiceStateClient() {
        val original = RestAPI.apiOrNull
        val originalSerializeNulls = runCatching { RestAPI.apiSerializeNulls }.getOrNull()
        val calls = mutableListOf<Pair<String, Array<out Any?>>>()
        val ordinary = recordingApi("ordinary", calls)
        val serializeNulls = recordingApi("serializeNulls", calls)
        try {
            RestAPI.install(ordinary, serializeNulls)

            StageChannelAPI.setUserSuppressedInChannel(channel(), 7L, true, meId = 7L)
            StageChannelAPI.setUserSuppressedInChannel(channel(), 8L, false, meId = 7L)

            assertEquals(listOf("serializeNulls:updateMyVoiceStates", "ordinary:updateUserVoiceStates"), calls.map { it.first })
            val selfBody = calls[0].second.last() as RestAPIParams.ChannelVoiceStateUpdate
            assertEquals(true, selfBody.suppress)
            assertEquals(null, selfBody.requestToSpeakTimestamp)
        } finally {
            if (original == null) RestAPI.reset()
            else RestAPI.install(original, originalSerializeNulls ?: original)
        }
    }

    private fun recordingApi(
        name: String,
        calls: MutableList<Pair<String, Array<out Any?>>>,
    ) = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, args ->
        calls += "$name:${method.name}" to args.orEmpty()
        Observable.just(null)
    } as RestAPIInterface

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
