package com.discord.utilities.voice

import com.discord.api.channel.Channel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.permission.Permission
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.voice.state.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [VoiceChannelJoinabilityUtils.computeJoinability]'s rule order.
 *
 * Every case here is a rule that shadows a later one, because the order is the whole behaviour:
 * getting it wrong silently locks users out of channels they may join, or lets them into ones they
 * may not.
 */
class VoiceChannelJoinabilityUtilsTest {

    private val allPermissions = Long.MAX_VALUE
    private val connectOnly = Permission.CONNECT

    @Test fun `private channels are always joinable, whatever the permissions say`() {
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(channel = channel(type = Channel.DM), channelPermissions = 0L),
        )
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(channel = channel(type = Channel.GROUP_DM), channelPermissions = 0L),
        )
    }

    @Test fun `the already-connected channel stays joinable even when it is full`() {
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(
                channel = channel(userLimit = 1),
                channelPermissions = 0L,
                channelVoiceStates = listOf(voiceState(userId = 2)),
                selectedVoiceChannelId = CHANNEL_ID,
            ),
        )
    }

    @Test fun `CONNECT is required before anything else is considered`() {
        assertEquals(
            VoiceChannelJoinability.PERMISSIONS_MISSING,
            compute(channelPermissions = 0L),
        )
    }

    @Test fun `a triggered verification level blocks the join`() {
        assertEquals(
            VoiceChannelJoinability.PERMISSIONS_MISSING,
            compute(
                channelPermissions = connectOnly,
                verificationLevelTriggered = GuildVerificationLevel.HIGH,
            ),
        )
    }

    @Test fun `a public stage instance is the exception to the verification gate`() {
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(
                channelPermissions = connectOnly,
                verificationLevelTriggered = GuildVerificationLevel.HIGH,
                stageInstance = stageInstance(StageInstancePrivacyLevel.PUBLIC),
            ),
        )
        assertEquals(
            VoiceChannelJoinability.PERMISSIONS_MISSING,
            compute(
                channelPermissions = connectOnly,
                verificationLevelTriggered = GuildVerificationLevel.HIGH,
                stageInstance = stageInstance(StageInstancePrivacyLevel.GUILD_ONLY),
            ),
        )
    }

    @Test fun `a full channel is refused, but not to someone who can move members`() {
        val occupants = listOf(voiceState(userId = 2), voiceState(userId = 3))
        assertEquals(
            VoiceChannelJoinability.CHANNEL_FULL,
            compute(
                channel = channel(userLimit = 2),
                channelPermissions = connectOnly,
                channelVoiceStates = occupants,
            ),
        )
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(
                channel = channel(userLimit = 2),
                channelPermissions = connectOnly or Permission.MOVE_MEMBERS,
                channelVoiceStates = occupants,
            ),
        )
    }

    @Test fun `a zero user limit means unlimited, not zero`() {
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(
                channel = channel(userLimit = 0),
                channelPermissions = connectOnly,
                channelVoiceStates = List(50) { voiceState(userId = it + 2L) },
            ),
        )
    }

    @Test fun `voice states for other channels do not count towards the limit`() {
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(
                channel = channel(userLimit = 1),
                channelPermissions = connectOnly,
                channelVoiceStates = listOf(voiceState(userId = 2, channelId = CHANNEL_ID + 1)),
            ),
        )
    }

    @Test fun `the guild video cap only applies when someone actually has video on`() {
        val cap = GuildMaxVideoChannelUsers.Limited(2)
        val silentOccupants = listOf(voiceState(userId = 2), voiceState(userId = 3))
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(
                channelPermissions = connectOnly,
                channelVoiceStates = silentOccupants,
                guildMaxVideoChannelUsers = cap,
            ),
        )
        assertEquals(
            VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY,
            compute(
                channelPermissions = connectOnly,
                channelVoiceStates = listOf(
                    voiceState(userId = 2, selfVideo = true),
                    voiceState(userId = 3),
                ),
                guildMaxVideoChannelUsers = cap,
            ),
        )
    }

    @Test fun `an administrator is never held back by the video cap`() {
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(
                channelPermissions = allPermissions,
                channelVoiceStates = listOf(
                    voiceState(userId = 2, selfVideo = true),
                    voiceState(userId = 3),
                ),
                guildMaxVideoChannelUsers = GuildMaxVideoChannelUsers.Limited(2),
            ),
        )
    }

    @Test fun `sitting exactly on the video cap is joinable for someone who can move members`() {
        val onTheCap = listOf(voiceState(userId = 2, selfVideo = true), voiceState(userId = 3))
        assertEquals(
            VoiceChannelJoinability.CAN_JOIN,
            compute(
                channelPermissions = connectOnly or Permission.MOVE_MEMBERS,
                channelVoiceStates = onTheCap,
                guildMaxVideoChannelUsers = GuildMaxVideoChannelUsers.Limited(2),
            ),
        )
        // One over the cap and the same permission no longer helps.
        assertEquals(
            VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY,
            compute(
                channelPermissions = connectOnly or Permission.MOVE_MEMBERS,
                channelVoiceStates = onTheCap + voiceState(userId = 4),
                guildMaxVideoChannelUsers = GuildMaxVideoChannelUsers.Limited(2),
            ),
        )
    }

    @Suppress("LongParameterList")
    private fun compute(
        channel: Channel = channel(),
        channelVoiceStates: Collection<VoiceState> = emptyList(),
        channelPermissions: Long? = allPermissions,
        guildMaxVideoChannelUsers: GuildMaxVideoChannelUsers = GuildMaxVideoChannelUsers.Unlimited,
        verificationLevelTriggered: GuildVerificationLevel = GuildVerificationLevel.NONE,
        selectedVoiceChannelId: Long? = null,
        stageInstance: StageInstance? = null,
    ) = VoiceChannelJoinabilityUtils.computeJoinability(
        channel = channel,
        channelVoiceStates = channelVoiceStates,
        channelPermissions = channelPermissions,
        guildMaxVideoChannelUsers = guildMaxVideoChannelUsers,
        verificationLevelTriggered = verificationLevelTriggered,
        selectedVoiceChannelId = selectedVoiceChannelId,
        stageInstance = stageInstance,
    )

    private fun channel(type: Int = Channel.GUILD_VOICE, userLimit: Int = 0) = Channel(
        topic = null, type = type, guildId = GUILD_ID, name = "voice", lastMessageId = 0,
        id = CHANNEL_ID, ownerId = 0, recipientIds = null, recipients = null, position = 0,
        permissionOverwrites = null, userLimit = userLimit, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false, parentId = 0,
        memberListId = null, rateLimitPerUser = 0, defaultAutoArchiveDuration = null,
        rtcRegion = null, flags = null, guildHashes = null, availableTags = null,
        appliedTags = null, threadMetadata = null, messageCount = null, memberCount = null,
        memberIdsPreview = null, member = null,
    )

    private fun voiceState(
        userId: Long,
        channelId: Long? = CHANNEL_ID,
        selfVideo: Boolean = false,
    ) = VoiceState(
        userId = userId, suppress = false, sessionId = "session-$userId", selfMute = false,
        selfDeaf = false, selfVideo = selfVideo, selfStream = false, mute = false,
        guildId = GUILD_ID, deaf = false, member = null, requestToSpeakTimestamp = null,
        channelId = channelId,
    )

    private fun stageInstance(privacyLevel: StageInstancePrivacyLevel) = StageInstance(
        id = 5, guildId = GUILD_ID, channelId = CHANNEL_ID, topic = "topic",
        privacyLevel = privacyLevel, inviteCode = null,
    )

    private companion object {
        const val GUILD_ID = 1L
        const val CHANNEL_ID = 10L
    }
}
