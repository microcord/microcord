package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelRecipientNick
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isCategory
import com.discord.api.channel.isDM
import com.discord.api.channel.isManaged
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isThread
import com.discord.api.guild.Guild
import com.discord.api.permission.Permission
import com.discord.api.user.User
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import rx.Observable

/**
 * Every guild channel, private channel and active thread the client knows about.
 *
 * Two shapes of the same data are kept deliberately. `channelsByGuild`/`threadsById` are the live
 * maps and may only be touched on the store thread — the `*Internal` accessors are for other stores
 * running inside the same dispatch. The `*Snapshot` fields are republished once per dispatch by
 * [snapshotData] and are the only safe read for observers and the UI.
 */
class StoreChannels(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    /**
     * Wired from `StorePermissions`, which cannot be a constructor dependency because it depends on
     * this store. An unwired instance makes [observeDefaultChannel] emit nothing.
     */
    private val observeChannelPermissionsForGuild: (Long) -> Observable<Map<Long, Long>> = { Observable.just(emptyMap()) },
    private val getLurkingGuildIds: () -> List<Long> = { emptyList() },
    private val isAuthenticated: () -> Boolean = { false },
    private val channelsCache: Persister<List<Channel>> = Persister(CHANNELS_CACHE_KEY, emptyList()),
) : StoreV2() {
    private val channelNames = HashMap<Long, String>()
    private val channelsByGuild = HashMap<Long, MutableMap<Long, Channel>>()
    private val threadsById = HashMap<Long, Channel>()

    /**
     * Threads discovered from message history before `READY` landed.
     *
     * They cannot be stored yet — their parent channel and therefore their nsfw flag is unknown —
     * so they are parked here and replayed at the end of [handleConnectionOpen].
     */
    private val preloadedThreads = HashMap<Long, Channel>()
    private var handledReadyPayload = false
    private var initializedForAuthedUser = false
    private var isConnectionOpen = false
    private var isStoreInitTimedOut = false

    private var channelNamesSnapshot: Map<Long, String> = emptyMap()
    private var channelsByGuildSnapshot: Map<Long, Map<Long, Channel>> = emptyMap()
    private var channelsByIdSnapshot: Map<Long, Channel> = emptyMap()
    private var threadsByIdSnapshot: Map<Long, Channel> = emptyMap()

    init { channelsByGuild[PRIVATE_CHANNELS_ID] = HashMap() }

    @StoreThread
    fun init() {
        channelsCache.get().filterNotNull().forEach { channel ->
            channelsByGuild.getOrPut(channel.guildId) { HashMap() }[channel.id] = channel
        }
        updateInitializationState()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        val channels = channelsByGuild.values.flatMap { it.values }
        val lurkingChannelIds = getLurkingGuildIds().flatMap { channelsByGuild[it].orEmpty().keys }.toSet()
        channelsCache.set(channels.filterNot { it.id in lurkingChannelIds })
        channelsByIdSnapshot = channels.associateBy(Channel::id)
        channelsByGuildSnapshot = channelsByGuild.mapValues { it.value.toMap() }
        channelNamesSnapshot = channelNames.toMap()
        threadsByIdSnapshot = threadsById.toMap()
    }

    @StoreThread
    fun reset() {
        channelsByGuild.clear()
        channelsByGuild[PRIVATE_CHANNELS_ID] = HashMap()
        channelNames.clear()
        threadsById.clear()
        preloadedThreads.clear()
        handledReadyPayload = false
        initializedForAuthedUser = false
        isConnectionOpen = false
        isStoreInitTimedOut = false
        channelsCache.clear()
        markChanged(InitializedUpdateSource)
    }

    @StoreThread
    fun handleConnected(connected: Boolean) { isConnectionOpen = connected }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        isConnectionOpen = true
        channelsByGuild.clear()
        channelNames.clear()
        threadsById.clear()
        val readyUsers = (payload.users.orEmpty() + listOfNotNull(payload.me)).associateBy(User::id)
        channelsByGuild[PRIVATE_CHANNELS_ID] = payload.privateChannels.orEmpty()
            // Application-owned channels are never shown; the reference filters them, not threads.
            .filterNot(Channel::isManaged)
            .map { channel ->
                if (!channel.recipients.isNullOrEmpty()) channel else channel.copy(
                    recipients = channel.recipientIds.orEmpty().mapNotNull(readyUsers::get),
                )
            }
            .associateByTo(HashMap(), Channel::id)
        payload.guilds.orEmpty().forEach(::storeGuild)
        preloadedThreads.values.filterNot { it.id in threadsById }.forEach { storeThread(it, it.guildId) }
        preloadedThreads.clear()
        rebuildNames()
        handledReadyPayload = true
        updateInitializationState()
        markChanged()
    }

    /**
     * Adds the DMs READY left out because the client already had them cached.
     *
     * These are additive: READY carried whichever conversations the server judged hot, and this is
     * the remainder, so the two together are the whole list.
     */
    @StoreThread
    fun handleConnectionOpenSupplemental(channels: List<Channel>, readyUsers: Map<Long, User>) {
        if (channels.isEmpty()) return
        val privateChannels = channelsByGuild.getOrPut(PRIVATE_CHANNELS_ID) { HashMap() }
        channels.filterNot(Channel::isManaged).forEach { channel ->
            privateChannels[channel.id] = if (!channel.recipients.isNullOrEmpty()) channel else channel.copy(
                recipients = channel.recipientIds.orEmpty().mapNotNull(readyUsers::get),
            )
        }
        rebuildNames()
        markChanged()
    }

    @StoreThread
    fun handleGuildAdd(guild: Guild) {
        storeGuild(guild)
        // A guild arriving before READY — the one asked for by `initial_guild_id` — is enough to
        // render, so the screen does not have to wait out the rest of the payload.
        updateInitializationState()
        markChanged()
    }

    @StoreThread
    fun handleGuildRemove(guild: Guild) {
        channelsByGuild.remove(guild.id)?.keys?.forEach(channelNames::remove)
        threadsById.values.removeAll { it.guildId == guild.id }
        markChanged()
    }

    @StoreThread
    fun handleChannelOrThreadCreateOrUpdate(channel: Channel) {
        if (channel.isManaged()) return
        val guildId = if (channel.isPrivate()) PRIVATE_CHANNELS_ID else channel.guildId
        if (channel.isThread()) {
            storeThread(channel, guildId)
            markChanged()
            return
        }
        val guildChannels = channelsByGuild.getOrPut(guildId) { HashMap() }
        val existing = guildChannels[channel.id]
        if (existing == channel) return
        val stored = channel.copy(
            guildId = channel.guildId.takeUnless { it == 0L } ?: guildId,
            // A partial CHANNEL_UPDATE for a group DM omits recipients; keep the ones we have.
            recipients = channel.recipients ?: existing?.recipients,
        )
        guildChannels[channel.id] = stored
        channelNames[channel.id] = stored.getDisplayName()
        if (existing?.nsfw != stored.nsfw) {
            // nsfw is inherited by threads from their parent, so it has to be pushed down.
            threadsById.values.filter { it.parentId == channel.id }.forEach { storeThread(it, guildId, stored) }
        }
        markChanged()
    }

    @StoreThread
    fun handleThreadCreateOrUpdate(channel: Channel) {
        if (channel.isThread()) handleChannelOrThreadCreateOrUpdate(channel)
    }

    @StoreThread
    fun handleChannelOrThreadDelete(channel: Channel) {
        val guildId = if (channel.isPrivate()) PRIVATE_CHANNELS_ID else channel.guildId
        var removed = channelsByGuild[guildId]?.remove(channel.id) != null
        removed = channelNames.remove(channel.id) != null || removed
        removed = threadsById.remove(channel.id) != null || removed
        if (removed) markChanged()
    }

    @StoreThread
    fun handleGroupDMRecipient(channelId: Long, user: User, nick: String?, added: Boolean) {
        val privateChannels = channelsByGuild[PRIVATE_CHANNELS_ID] ?: return
        val channel = privateChannels[channelId] ?: return
        val recipients = if (added) {
            channel.recipients.orEmpty() + user
        } else {
            channel.recipients.orEmpty().filterNot { it.id == user.id }
        }
        val nicks = nick?.let { value ->
            channel.nicks.orEmpty().filterNot { it.id == user.id } + ChannelRecipientNick(user.id, value)
        } ?: channel.nicks
        val updated = channel.copy(recipients = recipients, nicks = nicks)
        privateChannels[channelId] = updated
        channelNames[channelId] = updated.getDisplayName()
        markChanged()
    }

    @StoreThread
    fun handleThreadListSync(guildId: Long, threads: Collection<Channel>) {
        threads.filter(Channel::isThread).forEach { storeThread(it, guildId) }
        if (threads.isNotEmpty()) markChanged()
    }

    @StoreThread
    fun handleMessagesLoaded(threads: Collection<Channel>) = handleDiscoveredThreads(threads)

    @StoreThread
    fun handleSearchFinish(threads: Collection<Channel>) = handleDiscoveredThreads(threads)

    @StoreThread
    fun preloadThread(thread: Channel) { preloadedThreads[thread.id] = thread }

    fun onGroupCreated(channel: Channel) = dispatcher.schedule { handleChannelOrThreadCreateOrUpdate(channel) }

    @StoreThread
    fun handleStoreInitTimeout() {
        isStoreInitTimedOut = true
        updateInitializationState()
    }

    // -- Snapshot reads (safe off the store thread) ----------------------------------------------

    fun getChannel(channelId: Long): Channel? = channelsByIdSnapshot[channelId] ?: threadsByIdSnapshot[channelId]
    fun getAllChannels(): Map<Long, Channel> = channelsByIdSnapshot + threadsByIdSnapshot

    /** Threads first, then any guild; reads the published snapshots. */
    fun findChannelById(channelId: Long): Channel? = threadsByIdSnapshot[channelId]
        ?: channelsByGuildSnapshot.values.firstNotNullOfOrNull { it[channelId] }

    fun getChannelsForGuild(guildId: Long): Map<Long, Channel> = channelsByGuildSnapshot[guildId].orEmpty()
    fun getChannelNames(): Map<Long, String> = channelNamesSnapshot
    fun getInitializedForAuthedUser(): Boolean = initializedForAuthedUser

    // -- Live reads (store thread only) ----------------------------------------------------------

    /** [findChannelById] against the live maps, for other stores inside the same dispatch. */
    @StoreThread
    internal fun findChannelByIdInternal(channelId: Long): Channel? = threadsById[channelId]
        ?: channelsByGuild.values.firstNotNullOfOrNull { it[channelId] }

    @StoreThread
    internal fun getChannelsByGuildInternal(): Map<Long, Map<Long, Channel>> = channelsByGuild

    @StoreThread
    internal fun getChannelsByIdInternal(): Map<Long, Channel> =
        channelsByGuild.values.flatMap { it.values }.associateBy(Channel::id)

    @StoreThread
    internal fun getChannelsForGuildInternal(guildId: Long): Map<Long, Channel>? = channelsByGuild[guildId]

    @StoreThread
    internal fun getThreadsByIdInternal(): Map<Long, Channel> = threadsById

    @StoreThread
    internal fun getChannelNamesInternal(): Map<Long, String> = channelNames

    @StoreThread
    internal fun getChannelInternal(guildId: Long, channelId: Long): Channel? =
        channelsByGuild[guildId]?.get(channelId) ?: threadsById[channelId]

    @StoreThread
    internal fun getGuildChannelInternal(guildId: Long, channelId: Long): Channel? =
        channelsByGuild[guildId]?.get(channelId)

    @StoreThread
    internal fun findChannelsByCategoryInternal(guildId: Long, categoryId: Long): List<Channel> =
        channelsByGuild[guildId].orEmpty().values.filter { it.parentId == categoryId }

    @StoreThread
    internal fun getThreadsForChannelInternal(channelId: Long): List<Channel> =
        threadsById.values.filter { it.parentId == channelId }

    @StoreThread
    internal fun getThreadsForGuildInternal(guildId: Long): List<Channel> =
        threadsById.values.filter { it.guildId == guildId }

    @StoreThread
    internal fun isAuthenticatedInternal(): Boolean = isAuthenticated()

    @StoreThread
    internal fun setInitializedForAuthedUser(initialized: Boolean) { initializedForAuthedUser = initialized }

    // -- Observables -----------------------------------------------------------------------------

    fun observeAllChannels(): Observable<Map<Long, Channel>> = observe { channelsByIdSnapshot + threadsByIdSnapshot }
    fun observeGuildAndPrivateChannels(): Observable<Map<Long, Channel>> = observe { channelsByIdSnapshot }
    fun observeChannel(channelId: Long): Observable<Channel?> = observeAllChannels().map { it[channelId] }.distinctUntilChanged()
    fun observeChannelsForGuild(guildId: Long, type: Int? = null): Observable<Map<Long, Channel>> = observe {
        channelsByGuildSnapshot[guildId].orEmpty().let { channels ->
            if (type == null) channels else channels.filterValues { it.type == type }
        }
    }.distinctUntilChanged()
    fun observePrivateChannels() = observeChannelsForGuild(PRIVATE_CHANNELS_ID)
    fun observePrivateChannel(channelId: Long): Observable<Channel?> =
        observePrivateChannels().map { it[channelId] }.distinctUntilChanged()
    fun observeDMs(): Observable<List<Channel>> =
        observePrivateChannels().map { it.values.filter(Channel::isDM) }
    fun observeChannelCategories(guildId: Long): Observable<List<Channel>> =
        observeChannelsForGuild(guildId).map { it.values.filter(Channel::isCategory) }
            .distinctUntilChanged()
    fun observeNames(): Observable<Map<Long, String>> = observe { channelNamesSnapshot }.distinctUntilChanged()
    fun observeNames(channelIds: Collection<Long>): Observable<Map<Long, String>> =
        observeNames().map { names -> names.filterKeys(channelIds.toSet()::contains) }.distinctUntilChanged()
    fun observeInitializedForAuthedUser(): Observable<Boolean> =
        observationDeck.connectRx(InitializedUpdateSource, generator = { initializedForAuthedUser })
    fun observeIds(includeThreads: Boolean = false): Observable<Map<Long, List<Long>>> =
        (if (includeThreads) observeAllChannels() else observeGuildAndPrivateChannels()).map { channels ->
            channels.values.groupBy(Channel::guildId).mapValues { it.value.map(Channel::id) }
        }.distinctUntilChanged()

    /**
     * The channel a guild opens to when the user has no selection.
     *
     * Both sources must be live: permissions change without the channel list moving (a role edit)
     * and channels change without permissions moving (a channel being created), so this switches
     * over the permission stream into the channel stream rather than reading a snapshot inside a
     * `map`, which would never re-emit on the second case.
     */
    fun observeDefaultChannel(guildId: Long): Observable<Channel?> =
        observeChannelPermissionsForGuild(guildId).switchMap { guildChannelPermissions ->
            observeChannelsForGuild(guildId, Channel.GUILD_TEXT).map { guildChannels ->
                guildChannels.values
                    .filter { PermissionUtils.can(Permission.VIEW_CHANNEL, guildChannelPermissions[it.id]) }
                    .minWithOrNull(Channel.getSortByNameAndType())
            }
        }.distinctUntilChanged()

    fun observeThreadsFromMessages(threadIds: Collection<Long>): Observable<Map<Long, Channel>> =
        observeAllChannels().map { threadsByIdSnapshot.filterKeys(threadIds.toSet()::contains) }.distinctUntilChanged()

    // -- Internals -------------------------------------------------------------------------------

    @StoreThread
    private fun storeGuild(guild: Guild) {
        if (guild.unavailable) return
        val channels = channelsByGuild.getOrPut(guild.id) { HashMap() }
        guild.channels.orEmpty().forEach { channel ->
            val stored = channel.copy(guildId = channel.guildId.takeUnless { it == 0L } ?: guild.id)
            channels[stored.id] = stored
            channelNames[stored.id] = stored.getDisplayName()
        }
        guild.threads.orEmpty().filter(Channel::isThread).forEach { storeThread(it, guild.id) }
    }

    /**
     * Store [thread] under its parent's guild, inheriting the parent's nsfw flag.
     *
     * [parentChannel] lets a caller that already holds the parent seed the flag directly, which is
     * what makes a parent's nsfw change propagate to threads that were stored before it.
     */
    @StoreThread
    internal fun storeThread(thread: Channel, fallbackGuildId: Long = 0L, parentChannel: Channel? = null) {
        val resolvedGuildId = thread.guildId.takeUnless { it == 0L } ?: fallbackGuildId
        val parentNsfw = parentChannel?.nsfw
            ?: getChannelInternal(thread.guildId, thread.parentId)?.nsfw
            ?: thread.nsfw
        threadsById[thread.id] = thread.copy(guildId = resolvedGuildId, nsfw = parentNsfw)
        channelNames[thread.id] = thread.getDisplayName()
    }

    @StoreThread
    private fun handleDiscoveredThreads(threads: Collection<Channel>) {
        var changed = false
        threads.filter(Channel::isThread).forEach { thread ->
            if (thread.id !in threadsById) {
                storeThread(thread, thread.guildId)
                changed = true
            }
            if (!isConnectionOpen) preloadedThreads[thread.id] = thread
        }
        if (changed) markChanged()
    }

    @StoreThread
    private fun rebuildNames() {
        channelNames.clear()
        channelsByGuild.values.flatMap { it.values }.forEach { channelNames[it.id] = it.getDisplayName() }
        threadsById.values.forEach { channelNames[it.id] = it.getDisplayName() }
    }

    @StoreThread
    private fun updateInitializationState() {
        val initialized = isAuthenticated() &&
            (channelsByGuild.values.any { it.isNotEmpty() } || handledReadyPayload || isStoreInitTimedOut)
        if (!initializedForAuthedUser && initialized) {
            initializedForAuthedUser = true
            markChanged(InitializedUpdateSource)
        }
    }

    private fun <T> observe(generator: () -> T): Observable<T> = observationDeck.connectRx(this, generator = generator)

    companion object {
        const val PRIVATE_CHANNELS_ID = 0L
        const val CHANNELS_CACHE_KEY = "STORE_CHANNELS_V26"
        val InitializedUpdateSource = object : ObservationDeck.UpdateSource {}
    }
}
