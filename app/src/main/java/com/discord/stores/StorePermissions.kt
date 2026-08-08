package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.guild.Guild as ApiGuild
import com.discord.api.guildmember.GuildMember as ApiGuildMember
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionBit
import com.discord.api.stageinstance.StageInstance
import com.discord.api.thread.ThreadMemberUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.models.domain.ModelPayload
import com.discord.models.message.Message
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import rx.Observable

/**
 * The current user's effective permissions, per guild and per channel.
 *
 * Permissions are recomputed rather than received: the gateway sends roles and overwrites, and the
 * client folds them together. Because almost every screen gates on the result, the computation is
 * done once here and cached, and the caches are persisted so a cold start can render the channel
 * list before `READY` arrives.
 *
 * Two inputs beyond roles and overwrites change the answer and must be supplied:
 *
 * * **stage instances** — a lurker in a channel with a live public stage gets a wider allow-list
 *   than the channel overwrites alone would give, so stage speakers resolve correctly;
 * * **thread membership** — a private thread the user has not joined is invisible unless they can
 *   manage threads, which cannot be derived from the channel object.
 */
class StorePermissions(
    private val storeUser: StoreUser,
    private val storeChannels: StoreChannels,
    private val storeGuilds: StoreGuilds,
    private val observationDeck: ObservationDeck,
    private val permissionsForGuildsCache: Persister<Map<Long, Long>> =
        Persister(STORE_GUILD_PERMISSIONS_V5, emptyMap()),
    private val permissionsForChannelsCache: Persister<Map<Long, Map<Long, Long>>> =
        Persister(STORE_CHANNEL_PERMISSIONS_BY_GUILD_V5, emptyMap()),
    private val storeStageInstances: StoreStageInstances? = null,
    private val storeThreadsJoined: StoreThreadsJoined? = null,
) : StoreV2() {
    private val permissionsForGuilds = HashMap<Long, Long>()
    private val permissionsForChannels = HashMap<Long, Map<Long, Long>>()
    private var permissionsForGuildsSnapshot: Map<Long, Long> = emptyMap()
    private var permissionsForChannelsSnapshot: Map<Long, Map<Long, Long>> = emptyMap()
    private var permissionsForChannelsFlattenedSnapshot: Map<Long, Long> = emptyMap()

    private val meId: Long
        get() = storeUser.getMeInternal().id

    override fun init(context: Context) = init()

    fun init() {
        restoreFromCache()
        markChanged()
    }

    fun reset() {
        permissionsForGuilds.clear()
        permissionsForChannels.clear()
        permissionsForGuildsCache.clear()
        permissionsForChannelsCache.clear()
        markChanged()
    }

    fun getGuildPermissions(): Map<Long, Long> = permissionsForGuildsSnapshot
    fun getPermissionsByChannel(): Map<Long, Long> = permissionsForChannelsFlattenedSnapshot
    internal fun getPermissionsByChannelInternal(guildId: Long): Map<Long, Long> =
        permissionsForChannels[guildId].orEmpty()
    internal fun getPermissionsByChannelInternal(): Map<Long, Long> =
        permissionsForChannels.values.flatMap { it.entries }.associate { it.toPair() }

    fun observeAllPermissions(): Observable<Map<Long, Map<Long, Long>>> =
        observationDeck.connectRx(this) { permissionsForChannelsSnapshot }
    fun observeChannelPermissionsForGuild(guildId: Long): Observable<Map<Long, Long>> =
        observeAllPermissions().map { it[guildId].orEmpty() }.distinctUntilChanged()
    fun observePermissionsForAllChannels(): Observable<Map<Long, Long>> =
        observationDeck.connectRx(this) { permissionsForChannelsFlattenedSnapshot }.distinctUntilChanged()
    fun observePermissionsForChannel(channelId: Long): Observable<Long?> =
        observationDeck.connectRx(this) { permissionsForChannelsFlattenedSnapshot[channelId] }
            .distinctUntilChanged()
    fun observePermissionsForGuild(guildId: Long): Observable<Long?> =
        observationDeck.connectRx(this) { permissionsForGuildsSnapshot[guildId] }.distinctUntilChanged()

    // region gateway handlers

    @StoreThread
    fun handleConnectionOpen() {
        recomputeAllPermissions()
        markChanged()
    }

    @StoreThread
    fun handleConnectionOpen(@Suppress("UNUSED_PARAMETER") payload: ModelPayload) = handleConnectionOpen()

    @StoreThread
    fun handleGuildAdd(guild: ApiGuild) =
        updateGuildPermissions(guild.id, storeChannels.getThreadsForGuildInternal(guild.id))

    @StoreThread
    fun handleGuildRemove(guild: ApiGuild) {
        permissionsForChannels.remove(guild.id)
        permissionsForGuilds.remove(guild.id)
        markChanged()
    }

    @StoreThread
    fun handleGuildMemberAdd(member: ApiGuildMember) {
        if (meId == 0L || member.user.id != meId) return
        updateGuildPermissions(member.guildId, storeChannels.getThreadsForGuildInternal(member.guildId))
    }

    @StoreThread
    fun handleGuildRolesChanged(guildId: Long) =
        updateGuildPermissions(guildId, storeChannels.getThreadsForGuildInternal(guildId))

    /** One channel changed, so only that channel is recomputed and merged back in. */
    @StoreThread
    fun handleChannelOrThreadCreateOrUpdate(channel: Channel) = updateChannelPermissions(channel)

    @StoreThread
    fun handleChannelOrThreadDelete(channel: Channel) {
        permissionsForChannels[channel.guildId] =
            permissionsForChannels[channel.guildId].orEmpty() - channel.id
        markChanged()
    }

    @StoreThread
    fun handleChannelCreateOrUpdate(channel: Channel) = handleChannelOrThreadCreateOrUpdate(channel)

    @StoreThread
    fun handleChannelDelete(channel: Channel) = handleChannelOrThreadDelete(channel)

    @StoreThread
    fun handleThreadListSync(threadListSync: ModelThreadListSync) {
        if (meId == 0L) return
        updateGuildPermissions(
            threadListSync.guildId,
            storeChannels.getThreadsForGuildInternal(threadListSync.guildId),
        )
    }

    @StoreThread
    fun handleThreadMemberUpdate(threadMemberUpdate: ThreadMemberUpdate) {
        val thread = storeChannels.getGuildChannelInternal(threadMemberUpdate.guildId, threadMemberUpdate.id)
            ?: return
        updateChannelPermissions(thread)
    }

    /**
     * Only *our own* membership changes the answer, so a busy thread with hundreds of joins does
     * not trigger a recompute per event.
     */
    @StoreThread
    fun handleThreadMembersUpdate(threadMembersUpdate: ThreadMembersUpdate) {
        if (meId == 0L) return
        val thread = storeChannels.getGuildChannelInternal(threadMembersUpdate.guildId, threadMembersUpdate.id)
            ?: return
        val meAdded = threadMembersUpdate.addedMembers?.any { it.userId == meId } == true
        val meRemoved = threadMembersUpdate.removedMemberIds?.contains(meId) == true
        if (meAdded || meRemoved) updateChannelPermissions(thread)
    }

    /**
     * A page of history can carry thread starter channels the client has not seen; their guild is
     * recomputed so the newly known threads get permissions.
     */
    @StoreThread
    fun handleMessagesLoaded(messages: List<Message>) {
        if (meId == 0L) return
        val guildId = messages.mapNotNull(Message::thread).firstOrNull()?.guildId ?: return
        updateGuildPermissions(guildId, storeChannels.getThreadsForGuildInternal(guildId))
    }

    /**
     * Search results carry threads the same way history does.
     *
     * Note the reference reads `hits.map { it.thread }.firstOrNull()` rather than `mapNotNull`,
     * so a first hit without a thread ends the check — that behaviour is preserved.
     *
     * Takes the hit list rather than `SearchState` because `utilities/search/network/state` does
     * not exist in this project yet.
     */
    @StoreThread
    fun handleSearchFinish(hits: List<Message>?) {
        if (meId == 0L) return
        val guildId = hits?.map(Message::thread)?.firstOrNull()?.guildId ?: return
        updateGuildPermissions(guildId, storeChannels.getThreadsForGuildInternal(guildId))
    }

    /** Starting or ending a stage widens or narrows what a lurker in that channel may do. */
    @StoreThread
    fun handleStageInstanceChange(stageInstance: StageInstance) {
        if (meId == 0L) return
        val channel = storeChannels.getChannelInternal(stageInstance.guildId, stageInstance.channelId) ?: return
        updateChannelPermissions(channel)
    }

    // endregion

    /**
     * Rebuilds everything from scratch.
     *
     * Guilds are enumerated from the channel store rather than the guild store, because a guild
     * with no channels has nothing to compute; guild id 0 is the bucket holding private channels,
     * which have no guild permissions at all.
     */
    @StoreThread
    fun recomputeAllPermissions() {
        permissionsForGuilds.clear()
        permissionsForChannels.clear()
        val threadsByGuild = storeChannels.getThreadsByIdInternal().values.groupBy(Channel::guildId)
        storeChannels.getChannelsByGuildInternal().keys
            .filter { it != 0L }
            .forEach { guildId -> updateGuildPermissions(guildId, threadsByGuild[guildId].orEmpty()) }
    }

    @StoreThread
    private fun restoreFromCache() {
        permissionsForGuilds.putAll(permissionsForGuildsCache.get())
        permissionsForChannels.putAll(permissionsForChannelsCache.get())
    }

    @StoreThread
    private fun updateGuildPermissions(guildId: Long, guildThreads: Collection<Channel>) {
        if (meId == 0L) return
        val channels = storeChannels.getChannelsByGuildInternal()[guildId] ?: return
        val guild = storeGuilds.getGuildsInternal()[guildId] ?: return
        val member = storeGuilds.getGuildMembersComputedInternal()[guildId]?.get(meId)
        val roles = storeGuilds.getGuildRolesInternal()[guildId]

        val guildPermissions = PermissionUtils.computeNonThreadPermissions(
            meId, guildId, guild.ownerId, member, roles, null,
        )
        if (permissionsForGuilds[guildId] != guildPermissions) {
            permissionsForGuilds[guildId] = guildPermissions
            markChanged()
        }

        val channelPermissions = computeChannelPermissions(guildId, channels.values + guildThreads)
        if (permissionsForChannels[guildId] != channelPermissions) {
            permissionsForChannels[guildId] = channelPermissions
            markChanged()
        }
    }

    /** Recomputes a single channel and merges it into its guild's map. */
    @StoreThread
    private fun updateChannelPermissions(channel: Channel) {
        if (meId == 0L) return
        val guildId = channel.guildId
        permissionsForChannels[guildId] =
            permissionsForChannels[guildId].orEmpty() + computeChannelPermissions(guildId, listOf(channel))
        markChanged()
    }

    @StoreThread
    private fun computeChannelPermissions(guildId: Long, channels: Iterable<Channel>): Map<Long, PermissionBit> {
        val guild = storeGuilds.getGuildsInternal()[guildId] ?: return emptyMap()
        val members = storeGuilds.getGuildMembersComputedInternal()[guildId]
        val roles = storeGuilds.getGuildRolesInternal()[guildId]
        val stageInstances = storeStageInstances?.getStageInstancesForGuildInternal(guildId)
        val member = members?.get(meId)
        return channels.associate { channel ->
            channel.id to if (channel.isPrivate()) {
                // DMs and group DMs have no overwrites; everything is allowed except what the
                // channel type itself cannot do.
                Permission.ALL
            } else {
                PermissionUtils.computePermissions(
                    userId = meId,
                    channel = channel,
                    parentChannel = storeChannels.getGuildChannelInternal(channel.guildId, channel.parentId),
                    guildOwnerId = guild.ownerId,
                    member = member,
                    guildRoles = roles,
                    stageInstances = stageInstances,
                    hasJoinedThread = storeThreadsJoined?.hasJoinedInternal(channel.id) ?: false,
                )
            }
        }
    }

    @StoreThread
    override fun snapshotData() {
        permissionsForGuildsSnapshot = HashMap(permissionsForGuilds)
        permissionsForChannelsSnapshot = permissionsForChannels.mapValues { HashMap(it.value) }
        val flattened = HashMap<Long, Long>(permissionsForChannels.values.sumOf { it.size })
        permissionsForChannels.values.forEach(flattened::putAll)
        permissionsForChannelsFlattenedSnapshot = flattened
        permissionsForGuildsCache.set(permissionsForGuildsSnapshot, persist = true)
        permissionsForChannelsCache.set(permissionsForChannelsSnapshot, persist = true)
    }

    companion object {
        const val STORE_GUILD_PERMISSIONS_V5 = "STORE_GUILD_PERMISSIONS_V5"
        const val STORE_CHANNEL_PERMISSIONS_BY_GUILD_V5 = "STORE_CHANNEL_PERMISSIONS_BY_GUILD_V5"
    }
}
