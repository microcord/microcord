package com.discord.utilities.voice

import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.permission.Permission
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.voice.state.VoiceState
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStageInstances
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.StoreVoiceStates
import com.discord.utilities.guilds.GuildVerificationLevelUtils
import com.discord.utilities.permissions.PermissionUtils
import rx.Observable

/**
 * Decides whether the current user may join a given vocal channel, and why not when they may not.
 *
 * The rules, in the order the reference applies them:
 *
 * 1. Private channels (DM / group DM) are always joinable.
 * 2. The channel the user is already connected to is always joinable, whatever else is true.
 * 3. Without [Permission.CONNECT] the answer is [VoiceChannelJoinability.PERMISSIONS_MISSING].
 * 4. A triggered guild verification level blocks the join, *unless* the channel hosts a public
 *    stage instance — public stages are the one surface lurkers may enter.
 * 5. The channel's own `userLimit` blocks the join once reached, unless the user may
 *    [Permission.MOVE_MEMBERS].
 * 6. Finally the guild-wide video cap applies, but only when somebody in the channel actually has
 *    video on, and never to administrators. Sitting exactly *on* the cap is still joinable for a
 *    user who may move members.
 */
object VoiceChannelJoinabilityUtils {

    @Suppress("ReturnCount", "LongParameterList")
    fun computeJoinability(
        channel: Channel,
        channelVoiceStates: Collection<VoiceState>,
        channelPermissions: Long?,
        guildMaxVideoChannelUsers: GuildMaxVideoChannelUsers,
        verificationLevelTriggered: GuildVerificationLevel,
        selectedVoiceChannelId: Long?,
        stageInstance: StageInstance?,
    ): VoiceChannelJoinability {
        if (channel.isPrivate()) return VoiceChannelJoinability.CAN_JOIN
        if (selectedVoiceChannelId != null && channel.id == selectedVoiceChannelId) {
            return VoiceChannelJoinability.CAN_JOIN
        }
        if (!PermissionUtils.can(Permission.CONNECT, channelPermissions)) {
            return VoiceChannelJoinability.PERMISSIONS_MISSING
        }

        val verificationBlocks = verificationLevelTriggered != GuildVerificationLevel.NONE
        val publicStage = stageInstance != null && stageInstance.isPublic()
        if (verificationBlocks && !publicStage) {
            return VoiceChannelJoinability.PERMISSIONS_MISSING
        }

        val occupants = channelVoiceStates.filter { it.channelId == channel.id }
        val occupantCount = occupants.size
        val anyoneHasVideoOn = occupants.any { it.selfVideo }
        val canMoveMembers = PermissionUtils.can(Permission.MOVE_MEMBERS, channelPermissions)
        val userLimit = if (channel.userLimit != 0) channel.userLimit else Int.MAX_VALUE

        if (occupantCount >= userLimit && !canMoveMembers) {
            return VoiceChannelJoinability.CHANNEL_FULL
        }

        val isAdministrator = PermissionUtils.can(Permission.ADMINISTRATOR, channelPermissions)
        val atVideoCapacity = anyoneHasVideoOn &&
            guildMaxVideoChannelUsers is GuildMaxVideoChannelUsers.Limited &&
            occupantCount >= guildMaxVideoChannelUsers.limit

        if (atVideoCapacity && !isAdministrator) {
            val exactlyOnTheCap =
                (guildMaxVideoChannelUsers as GuildMaxVideoChannelUsers.Limited).limit == occupantCount
            return if (exactlyOnTheCap && canMoveMembers) {
                VoiceChannelJoinability.CAN_JOIN
            } else {
                VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY
            }
        }
        return VoiceChannelJoinability.CAN_JOIN
    }

    /** Snapshot form of [observeJoinability], for callers that cannot subscribe. */
    fun getJoinability(channelId: Long): VoiceChannelJoinability {
        val channel = StoreStream.getChannels().getChannel(channelId)
            ?: return VoiceChannelJoinability.CHANNEL_DOES_NOT_EXIST
        val guildVoiceStates = StoreStream.getVoiceStates().get()[channel.guildId].orEmpty()
        return computeJoinability(
            channel = channel,
            channelVoiceStates = guildVoiceStates.values.filter { it.channelId == channelId },
            channelPermissions = StoreStream.getPermissions().getPermissionsByChannel()[channelId],
            guildMaxVideoChannelUsers = StoreStream.getGuilds().getGuild(channel.guildId)
                ?.maxVideoChannelUsers ?: GuildMaxVideoChannelUsers.Unlimited,
            verificationLevelTriggered =
                GuildVerificationLevelUtils.getVerificationLevelTriggered(channel.guildId),
            selectedVoiceChannelId =
                StoreStream.getVoiceChannelSelected().getSelectedVoiceChannelId(),
            stageInstance = StoreStream.getStageInstances().getStageInstanceForChannel(channelId),
        )
    }

    @Suppress("LongParameterList")
    @JvmOverloads
    fun observeJoinability(
        channelId: Long,
        channelsStore: StoreChannels = StoreStream.getChannels(),
        guildsStore: StoreGuilds = StoreStream.getGuilds(),
        permissionsStore: StorePermissions = StoreStream.getPermissions(),
        voiceStatesStore: StoreVoiceStates = StoreStream.getVoiceStates(),
        voiceChannelSelectedStore: StoreVoiceChannelSelected =
            StoreStream.getVoiceChannelSelected(),
        stageInstancesStore: StoreStageInstances = StoreStream.getStageInstances(),
    ): Observable<VoiceChannelJoinability> = channelsStore.observeChannel(channelId)
        .switchMap { channel ->
            if (channel == null) {
                Observable.just(VoiceChannelJoinability.CHANNEL_DOES_NOT_EXIST)
            } else {
                Observable.combineLatest(
                    voiceStatesStore.observe(channel.guildId, channelId),
                    permissionsStore.observePermissionsForChannel(channelId),
                    guildsStore.observeGuild(channel.guildId),
                    GuildVerificationLevelUtils.observeVerificationLevelTriggered(channel.guildId),
                    voiceChannelSelectedStore.observeSelectedVoiceChannelId(),
                    stageInstancesStore.observeStageInstanceForChannel(channelId),
                ) { channelVoiceStates,
                    channelPermissions,
                    guild,
                    verificationLevelTriggered,
                    selectedVoiceChannelId,
                    stageInstance ->
                    computeJoinability(
                        channel = channel,
                        channelVoiceStates = channelVoiceStates.values,
                        channelPermissions = channelPermissions,
                        guildMaxVideoChannelUsers = guild?.maxVideoChannelUsers
                            ?: GuildMaxVideoChannelUsers.Unlimited,
                        verificationLevelTriggered = verificationLevelTriggered,
                        selectedVoiceChannelId = selectedVoiceChannelId,
                        stageInstance = stageInstance,
                    )
                }
            }
        }

    /**
     * Reference: `StageInstanceUtilsKt.isPublic`, which R8 emptied out of the export — the call
     * survives only as the inlined `privacyLevel == PUBLIC` test seen at the joinability call site.
     * Move this to `api/stageinstance/` if a second caller appears.
     */
    private fun StageInstance.isPublic(): Boolean =
        privacyLevel == StageInstancePrivacyLevel.PUBLIC
}
