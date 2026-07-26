package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.guildmember.GuildMember as ApiGuildMember
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstance
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.api.voice.state.VoiceState
import com.discord.models.guild.Guild
import com.discord.models.guild.UserGuildMember
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.stage.StageRoles
import rx.Observable

/**
 * A stage channel as the stage surfaces render it.
 *
 * **Belongs in `com.discord.widgets.stage.model`**, where the reference declares it. That package
 * does not exist in this target and this agent may only add files under `com.discord.stores`; move
 * it when the stage widgets are reconstructed. `WidgetChannelListModel.StageChannelInfo` is a
 * narrower placeholder for the same thing and should collapse into this type at the same time.
 */
data class StageChannel(
    val channel: Channel,
    /** Everyone with a role in the channel — speakers *and* audience. */
    val participants: List<User>,
    val myRoles: StageRoles?,
    val speakerIds: Set<Long>,
    val speakers: List<UserGuildMember>,
    /** `participants.size - speakerIds.size`. */
    val audienceSize: Int,
    val stageInstance: StageInstance?,
    /** Whether the current user may `CONNECT` *and* `VIEW_CHANNEL`. */
    val canAccess: Boolean,
    val containsMe: Boolean,
)

/**
 * Resolves what a single user's role in a stage channel is, given everything needed to compute it.
 *
 * **Belongs in its own `com.discord.stores.StageChannelRoleContext` file** — the package is right,
 * the file is not, because this agent's file list does not include it.
 *
 * Speaker-ness has two definitions and both are needed. A user *in* the channel is a speaker when
 * their voice state says [StageRequestToSpeakState.ON_STAGE] (i.e. not suppressed). A user with no
 * voice state — someone who has the ability but is not connected — is a speaker when they hold
 * `SPEAK`. Moderators are defined purely by permission
 * ([Permission.MODERATE_STAGE_CHANNEL] = `MANAGE_CHANNELS | MUTE_MEMBERS | MOVE_MEMBERS`).
 */
internal class StageChannelRoleContext(
    private val guild: Guild,
    private val channel: Channel,
    private val guildRolesMap: Map<Long, GuildRole>,
    private val voiceStates: Map<Long, VoiceState>,
) {
    fun getRequestToSpeakState(userId: Long): StageRequestToSpeakState =
        voiceStates[userId].stageRequestToSpeakState

    fun getRole(userId: Long, member: GuildMember): StageRoles = StageRoles(
        isSpeaker = isSpeaker(userId, member),
        isModerator = isModerator(userId, member),
        isInvitedToSpeak = getRequestToSpeakState(userId) ==
            StageRequestToSpeakState.REQUESTED_TO_SPEAK_AND_AWAITING_USER_ACK,
    )

    fun isModerator(userId: Long, member: GuildMember): Boolean =
        can(Permission.MODERATE_STAGE_CHANNEL, userId, member)

    private fun isSpeaker(userId: Long, member: GuildMember): Boolean =
        if (voiceStates[userId] != null) {
            getRequestToSpeakState(userId) == StageRequestToSpeakState.ON_STAGE
        } else {
            can(Permission.SPEAK, userId, member)
        }

    private fun can(permission: Long, userId: Long, member: GuildMember): Boolean =
        PermissionUtils.can(
            permission,
            PermissionUtils.computeNonThreadPermissions(
                userId,
                guild.id,
                guild.ownerId,
                member,
                guildRolesMap,
                channel.permissionOverwrites.orEmpty(),
            ),
        )
}

/**
 * Everyone's role in every stage channel, kept incrementally so a stage with hundreds of listeners
 * does not recompute on every voice state.
 *
 * The store owns two independent indexes:
 *
 * - **[stageRolesByChannel]** `channelId -> userId -> roles`. Only users who are *in* the channel
 *   appear; the entry is created when they join and removed when they leave.
 * - **[stageEventStates]** `guildId -> channelId -> moderators`. A separate, coarser index of who
 *   is moderating, published through its own [ObservationDeck.UpdateSource] so that
 *   "a moderator arrived" wakes the stage-start prompt without waking every role observer.
 *
 * **[snapshotData] is incremental, and that is the point of [dirtyChannelIds].** A full copy of the
 * role map on every dispatch would be O(participants) per voice packet. Instead only channels
 * touched since the last snapshot are re-copied and the rest are carried over by reference.
 *
 * Two reference behaviours preserved as-is:
 * - [handleConnectionOpen] clears the internal maps but does **not** mark changed, so the snapshot
 *   keeps the previous session's roles for untouched channels until something dirties them.
 * - Bots are excluded from member add/remove recomputation (`user.isBot`), because a bot joining a
 *   stage never changes anyone's role.
 */
class StoreStageChannels(
    private val userStore: StoreUser,
    private val guildsStore: StoreGuilds,
    private val channelsStore: StoreChannels,
    private val voiceStatesStore: StoreVoiceStates,
    private val permissionsStore: StorePermissions,
    private val stageInstancesStore: StoreStageInstances,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    /**
     * Reference: `StageChannelNotifications.INSTANCE`. That class is not reconstructed, so the
     * invite-to-speak toast/notification is inert by default.
     */
    private val notifications: StageChannelNotifications = StageChannelNotifications.NONE,
) : StoreV2() {

    /** Who is currently moderating one stage channel. */
    data class StageEventActivationState(val moderators: Set<Long> = emptySet())

    /** The invite-to-speak prompts a stage raises for the current user. */
    interface StageChannelNotifications {
        fun onInvitedToSpeak(channelId: Long)
        fun onInviteToSpeakRescinded()

        companion object {
            val NONE: StageChannelNotifications = object : StageChannelNotifications {
                override fun onInvitedToSpeak(channelId: Long) = Unit
                override fun onInviteToSpeakRescinded() = Unit
            }
        }
    }

    private val stageRolesByChannel = LinkedHashMap<Long, MutableMap<Long, StageRoles>>()
    private var stageRolesByChannelSnapshot: Map<Long, Map<Long, StageRoles>> = HashMap()
    private val stageEventStates = LinkedHashMap<Long, MutableMap<Long, StageEventActivationState>>()
    private val dirtyChannelIds = LinkedHashSet<Long>()

    // region reads

    fun getChannelRoles(channelId: Long): Map<Long, StageRoles>? = stageRolesByChannelSnapshot[channelId]

    @StoreThread
    fun getChannelRolesInternal(channelId: Long): Map<Long, StageRoles>? = stageRolesByChannel[channelId]

    /**
     * `null` when the channel has no role map at all (nobody has ever been in it);
     * [StageRoles.AUDIENCE] when the channel is known but this user has no entry.
     */
    fun getUserRoles(userId: Long, channelId: Long): StageRoles? =
        stageRolesByChannelSnapshot[channelId]?.let { it[userId] ?: StageRoles.AUDIENCE }

    @StoreThread
    fun getUserRolesInternal(userId: Long, channelId: Long): StageRoles? =
        stageRolesByChannel[channelId]?.let { it[userId] ?: StageRoles.AUDIENCE }

    fun getMyRoles(channelId: Long): StageRoles? = getUserRoles(userStore.getMe().id, channelId)

    @StoreThread
    fun getMyRolesInternal(channelId: Long): StageRoles? =
        getUserRolesInternal(userStore.getMeInternal().id, channelId)

    /** Moderators of [channel] other than [userId] — the count the "join to co-host" prompt shows. */
    @JvmOverloads
    fun getOtherModeratorsCountInChannel(
        channel: Channel,
        userId: Long = userStore.getMe().id,
    ): Int {
        val activation = stageEventStates[channel.guildId]?.get(channel.id) ?: return 0
        val size = activation.moderators.size
        return if (userId in activation.moderators) size - 1 else size
    }

    /**
     * Projects the guild's stage channels for the stage list.
     *
     * Everything is passed in rather than read from the stores so the caller controls consistency;
     * the defaults read the current snapshot of each store.
     */
    @JvmOverloads
    fun getStageChannelsInGuild(
        guildId: Long,
        channelsInGuild: Map<Long, Channel> = channelsStore.getChannelsForGuild(guildId),
        myId: Long = userStore.getMe().id,
        users: Map<Long, User> = userStore.getUsers(),
        voiceStates: Map<Long, VoiceState> = voiceStatesStore.get()[guildId].orEmpty(),
    ): Map<Long, StageChannel> = channelsInGuild.values
        .filter(Channel::isStageVoiceChannel)
        .map { channel ->
            val roles = stageRolesByChannelSnapshot[channel.id].orEmpty()
            val participants = roles.keys.mapNotNull { users[it] }
            val speakerIds = participants
                .filter { getUserRoles(it.id, channel.id)?.isSpeaker == true }
                .mapTo(LinkedHashSet(), User::id)
            val audienceSize = participants.size - speakerIds.size
            val channelPermissions = permissionsStore.getPermissionsByChannel()[channel.id]
            val canAccess = PermissionUtils.can(Permission.CONNECT, channelPermissions) &&
                PermissionUtils.can(Permission.VIEW_CHANNEL, channelPermissions)
            val guildMembers = guildsStore.getMembers()[guildId]
            val speakers = participants
                .filter { it.id in speakerIds }
                .map { user -> UserGuildMember(user, guildMembers?.get(user.id)) }
            StageChannel(
                channel = channel,
                participants = participants,
                myRoles = getMyRoles(channel.id),
                speakerIds = speakerIds,
                speakers = speakers,
                audienceSize = audienceSize,
                stageInstance = stageInstancesStore.getStageInstanceForChannel(channel.id),
                canAccess = canAccess,
                containsMe = voiceStates[myId]?.channelId == channel.id,
            )
        }
        .associateBy { it.channel.id }

    fun observeStageRolesByChannel(channelId: Long): Observable<Map<Long, StageRoles>?> =
        observationDeck.connectRx(this) { getChannelRoles(channelId) }

    fun observeRoles(): Observable<Map<Long, Map<Long, StageRoles>>> =
        observationDeck.connectRx(this) { stageRolesByChannelSnapshot }

    fun observeUserRoles(userId: Long, channelId: Long): Observable<StageRoles?> =
        observationDeck.connectRx(this, channelsStore) {
            channelsStore.getChannel(channelId)?.let { getUserRoles(userId, it.id) }
        }

    fun observeMyRoles(channelId: Long): Observable<StageRoles?> =
        observationDeck.connectRx(this, channelsStore) {
            channelsStore.getChannel(channelId)?.let { getMyRoles(it.id) }
        }

    fun observeUserRequestToSpeakState(
        userId: Long,
        channelId: Long,
    ): Observable<StageRequestToSpeakState> =
        observationDeck.connectRx(this, channelsStore, guildsStore, voiceStatesStore) {
            val channel = channelsStore.getChannel(channelId) ?: return@connectRx StageRequestToSpeakState.NONE
            roleContext(channel)?.getRequestToSpeakState(userId) ?: StageRequestToSpeakState.NONE
        }

    fun observeMyRequestToSpeakState(channelId: Long): Observable<StageRequestToSpeakState> =
        observationDeck.connectRx(this, channelsStore, userStore, voiceStatesStore) {
            val channel = channelsStore.getChannel(channelId) ?: return@connectRx StageRequestToSpeakState.NONE
            roleContext(channel)?.getRequestToSpeakState(userStore.getMe().id)
                ?: StageRequestToSpeakState.NONE
        }

    fun observeGuildStageChannels(guildId: Long): Observable<Map<Long, StageChannel>> =
        observationDeck.connectRx(
            this, channelsStore, userStore, voiceStatesStore, stageInstancesStore,
        ) { getStageChannelsInGuild(guildId) }.distinctUntilChanged()

    fun observeStageChannels(): Observable<Map<Long, StageChannel>> =
        observationDeck.connectRx(
            this, channelsStore, guildsStore, userStore, voiceStatesStore, stageInstancesStore,
        ) {
            guildsStore.getGuilds().keys.fold(emptyMap<Long, StageChannel>()) { acc, guildId ->
                acc + getStageChannelsInGuild(guildId)
            }
        }.distinctUntilChanged()

    // endregion

    // region gateway

    /**
     * Reference behaviour reproduced: the maps are emptied without [markChanged], so the published
     * snapshot is not rebuilt until the first channel is dirtied afterwards.
     */
    @StoreThread
    fun handleConnectionOpen() {
        stageRolesByChannel.clear()
        stageEventStates.clear()
    }

    /**
     * The single entry point for role recomputation, driven by `VOICE_STATE_UPDATE`.
     *
     * @param fromChannelId the channel the user was in before this update; the same as their
     *   current channel for an in-place change (mute, request to speak).
     *
     * Two halves: leaving [fromChannelId] when the new voice state no longer points at it, and
     * (re)computing their role in whichever channel the voice state points at now.
     */
    @StoreThread
    fun handleVoiceStatesUpdated(guildId: Long, fromChannelId: Long, userId: Long) {
        val guildVoiceStates = voiceStatesStore.getInternal()[guildId]
        val voiceState = guildVoiceStates?.get(userId)
        val toChannelId = voiceState?.channelId ?: fromChannelId
        val toChannel = channelsStore.getChannelInternal(guildId, toChannelId)
        val fromChannel = channelsStore.getChannelInternal(guildId, fromChannelId)

        if (fromChannel != null &&
            fromChannel.isStageVoiceChannel() &&
            voiceState?.channelId != fromChannelId
        ) {
            val removed = stageRolesByChannel[fromChannelId]?.remove(userId)
            if (removed != null) {
                handleUserLeftStageOrLostRoles(fromChannel, userId, removed)
                dirtyChannelIds.add(fromChannelId)
                markChanged()
            }
        }

        if (voiceState == null || toChannel == null || !toChannel.isStageVoiceChannel()) return
        val channelRoles = stageRolesByChannel.getOrPut(toChannelId, ::LinkedHashMap)
        val oldRoles = channelRoles[userId]
        val newRoles = computeUserRolesInDispatch(userId, toChannel, guildVoiceStates)
        if (newRoles == oldRoles) return
        handleUserRoleChange(toChannel, userId, oldRoles, newRoles)
        channelRoles[userId] = newRoles
        dirtyChannelIds.add(toChannelId)
        markChanged()
    }

    @StoreThread
    fun handleChannelDelete(channel: Channel) {
        if (!channel.isStageVoiceChannel()) return
        if (stageRolesByChannel.remove(channel.id) != null) {
            dirtyChannelIds.add(channel.id)
            markChanged()
        }
        val guildStates = stageEventStates[channel.guildId] ?: return
        if (guildStates.remove(channel.id) != null) markStageEventDirty()
    }

    @StoreThread
    fun handleGuildRemove(guild: com.discord.api.guild.Guild) {
        if (stageEventStates.remove(guild.id) != null) markStageEventDirty()
        val removed = channelsStore.getChannelsForGuild(guild.id).keys
            .filterTo(LinkedHashSet()) { stageRolesByChannel.remove(it) != null }
        if (removed.isEmpty()) return
        dirtyChannelIds.addAll(removed)
        markChanged()
    }

    /**
     * A member's roles changed, so their stage permissions may have. Only users already present in
     * a stage channel are recomputed — someone not in the channel has no role to update.
     */
    @StoreThread
    fun handleGuildMemberAdd(member: ApiGuildMember) {
        val userId = member.user.id
        if (userStore.getUsersInternal()[userId]?.isBot == true) return
        for (channelId in stageChannelIdsInGuildForDispatch(member.guildId)) {
            val channelRoles = stageRolesByChannel[channelId] ?: continue
            val oldRoles = channelRoles[userId] ?: continue
            val channel = channelsStore.getChannelsByIdInternal()[channelId] ?: continue
            val newRoles = computeUserRolesInDispatch(userId, channel)
            if (newRoles == oldRoles) continue
            channelRoles[userId] = newRoles
            handleUserRoleChange(channel, userId, oldRoles, newRoles)
            dirtyChannelIds.add(channelId)
            markChanged()
        }
    }

    /**
     * Note the asymmetry with [handleGuildMemberAdd]: the role entry itself is **not** removed,
     * only the moderator bookkeeping is updated. The user's voice state leaving the channel is what
     * removes the entry, via [handleVoiceStatesUpdated].
     */
    @StoreThread
    fun handleGuildMemberRemove(guildId: Long, userId: Long) {
        if (userStore.getUsersInternal()[userId]?.isBot == true) return
        for (channelId in stageChannelIdsInGuildForDispatch(guildId)) {
            val roles = stageRolesByChannel[channelId]?.get(userId) ?: continue
            val channel = channelsStore.getChannelsByIdInternal()[channelId] ?: continue
            handleUserLeftStageOrLostRoles(channel, userId, roles)
            dirtyChannelIds.add(channelId)
            markChanged()
        }
    }

    /** A guild role changed: recompute every known participant of every stage channel. */
    @StoreThread
    fun handleGuildRoleCreateOrUpdate(guildId: Long) {
        for (channelId in stageChannelIdsInGuildForDispatch(guildId)) {
            val channelRoles = stageRolesByChannel[channelId] ?: continue
            channelRoles.keys.toList().forEach { userId ->
                handleVoiceStatesUpdated(guildId, channelId, userId)
            }
        }
    }

    // endregion

    // region internals

    private fun stageChannelIdsInGuildForDispatch(guildId: Long): List<Long> =
        channelsStore.getChannelsForGuildInternal(guildId)
            ?.values
            ?.filter(Channel::isStageVoiceChannel)
            ?.map(Channel::id)
            .orEmpty()

    @StoreThread
    private fun computeUserRolesInDispatch(
        userId: Long,
        channel: Channel,
        voiceStates: Map<Long, VoiceState>? = null,
        guildRoles: Map<Long, GuildRole>? = null,
    ): StageRoles {
        val guild = guildsStore.getGuildsInternal()[channel.guildId] ?: return StageRoles.AUDIENCE
        val members = guildsStore.getGuildMembersComputedInternal()[channel.guildId]
            ?: return StageRoles.AUDIENCE
        val member = members[userId] ?: return StageRoles.AUDIENCE
        val roles = guildRoles ?: guildsStore.getGuildRolesInternal()[channel.guildId]
        val states = voiceStates ?: voiceStatesStore.getInternal()[channel.guildId]
            ?: return StageRoles.AUDIENCE
        return roleContext(channel, guild, states, roles)?.getRole(userId, member)
            ?: StageRoles.AUDIENCE
    }

    private fun roleContext(
        channel: Channel,
        guild: Guild? = null,
        voiceStates: Map<Long, VoiceState>? = null,
        guildRoles: Map<Long, GuildRole>? = null,
    ): StageChannelRoleContext? {
        val resolvedGuild = guild ?: guildsStore.getGuilds()[channel.guildId] ?: return null
        return StageChannelRoleContext(
            resolvedGuild,
            channel,
            guildRoles ?: guildsStore.getRoles()[resolvedGuild.id].orEmpty(),
            voiceStates ?: voiceStatesStore.getForChannel(resolvedGuild.id, channel.id),
        )
    }

    /**
     * Fans a role transition out to the moderator index and to the invite-to-speak notification.
     *
     * The notification only fires for the current user, and only on the *edges* of
     * [StageRoles.isInvitedToSpeak] — being invited raises it, having the invite withdrawn
     * rescinds it.
     */
    @StoreThread
    private fun handleUserRoleChange(
        channel: Channel,
        userId: Long,
        oldRoles: StageRoles?,
        newRoles: StageRoles,
    ) {
        val gainedModerator = newRoles.isModerator && oldRoles?.isModerator != true
        val gainedSpeaker = newRoles.isSpeaker && oldRoles?.isSpeaker != true
        if (gainedModerator || gainedSpeaker) {
            handleUserJoinedStageOrGainedRole(channel, userId, newRoles)
        }
        val lostModerator = oldRoles?.isModerator == true && !newRoles.isModerator
        val lostSpeaker = oldRoles?.isSpeaker == true && !newRoles.isSpeaker
        if (oldRoles != null && (lostModerator || lostSpeaker)) {
            handleUserLeftStageOrLostRoles(channel, userId, oldRoles, newRoles)
        }
        if (userId != userStore.getMeInternal().id || oldRoles == null) return
        if (!oldRoles.isInvitedToSpeak && newRoles.isInvitedToSpeak) {
            notifications.onInvitedToSpeak(channel.id)
        } else if (oldRoles.isInvitedToSpeak && !newRoles.isInvitedToSpeak) {
            notifications.onInviteToSpeakRescinded()
        }
    }

    @StoreThread
    private fun handleUserJoinedStageOrGainedRole(channel: Channel, userId: Long, roles: StageRoles) {
        if (!roles.isModerator && !roles.isSpeaker) return
        val guildStates = stageEventStates.getOrPut(channel.guildId, ::LinkedHashMap)
        val activation = guildStates.getOrPut(channel.id) { StageEventActivationState() }
        if (!roles.isModerator || userId in activation.moderators) return
        guildStates[channel.id] = activation.copy(moderators = activation.moderators + userId)
        markStageEventDirty()
    }

    @StoreThread
    private fun handleUserLeftStageOrLostRoles(
        channel: Channel,
        userId: Long,
        oldRoles: StageRoles,
        newRoles: StageRoles = StageRoles.AUDIENCE,
    ) {
        if (!oldRoles.isModerator && !oldRoles.isSpeaker) return
        val guildStates = stageEventStates[channel.guildId] ?: return
        val activation = guildStates[channel.id] ?: return
        if (newRoles.isModerator || !oldRoles.isModerator || userId !in activation.moderators) return
        guildStates[channel.id] = activation.copy(moderators = activation.moderators - userId)
        markStageEventDirty()
    }

    private fun markStageEventDirty() = markChanged(EventStateUpdateSource)

    // endregion

    /**
     * Copies only the channels touched since the last snapshot. Channels that are neither dirty nor
     * present in the previous snapshot are dropped — reproduced from the reference, and the reason
     * [handleConnectionOpen] leaves stale entries behind.
     */
    @StoreThread
    override fun snapshotData() {
        if (dirtyChannelIds.isNotEmpty()) {
            val previous = stageRolesByChannelSnapshot
            val next = HashMap<Long, Map<Long, StageRoles>>(stageRolesByChannel.size)
            stageRolesByChannel.forEach { (channelId, roles) ->
                if (channelId in dirtyChannelIds) {
                    next[channelId] = HashMap(roles)
                } else {
                    previous[channelId]?.let { next[channelId] = it }
                }
            }
            stageRolesByChannelSnapshot = next
        }
        dirtyChannelIds.clear()
    }

    companion object {
        /** `MANAGE_CHANNELS | MUTE_MEMBERS | MOVE_MEMBERS` — the reference's own constant. */
        const val CAN_MODERATE_STAGE_CHANNELS = Permission.MODERATE_STAGE_CHANNEL

        /**
         * A second update source, so a change to who is moderating notifies the stage-start prompt
         * without forcing every role observer to re-read.
         */
        internal val EventStateUpdateSource = object : ObservationDeck.UpdateSource {}
    }
}
