package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelRecipientNick
import com.discord.api.guild.Guild
import com.discord.api.user.User
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import rx.Observable

class StoreChannels(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val observeChannelPermissionsForGuild: (Long) -> Observable<Map<Long, Long>> = { Observable.just(emptyMap()) },
    private val getLurkingGuildIds: () -> List<Long> = { emptyList() },
    private val isAuthenticated: () -> Boolean = { false },
    private val channelsCache: Persister<List<Channel>> = Persister(CHANNELS_CACHE_KEY, emptyList()),
) : StoreV2() {
    private val channelNames = HashMap<Long, String>()
    private val channelsByGuild = HashMap<Long, MutableMap<Long, Channel>>()
    private val threadsById = HashMap<Long, Channel>()
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

    fun init() {
        channelsCache.get().filterNotNull().forEach { channel ->
            channelsByGuild.getOrPut(channel.guildId) { HashMap() }[channel.id] = channel
        }
        updateInitializationState()
        markChanged()
    }

    override fun snapshotData() {
        val channels = channelsByGuild.values.flatMap { it.values }
        val lurkingChannelIds = getLurkingGuildIds().flatMap { channelsByGuild[it].orEmpty().keys }.toSet()
        channelsCache.set(channels.filterNot { it.id in lurkingChannelIds })
        channelsByIdSnapshot = channels.associateBy(Channel::id)
        channelsByGuildSnapshot = channelsByGuild.mapValues { it.value.toMap() }
        channelNamesSnapshot = channelNames.toMap()
        threadsByIdSnapshot = threadsById.toMap()
    }

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

    fun handleConnected(connected: Boolean) { isConnectionOpen = connected }

    fun handleConnectionOpen(payload: ModelPayload) {
        isConnectionOpen = true
        channelsByGuild.clear()
        channelNames.clear()
        threadsById.clear()
        channelsByGuild[PRIVATE_CHANNELS_ID] = payload.privateChannels.orEmpty()
            .filterNot(::isThread).associateByTo(HashMap(), Channel::id)
        payload.guilds.orEmpty().forEach(::storeGuild)
        preloadedThreads.values.filterNot { it.id in threadsById }.forEach { storeThread(it, it.guildId) }
        preloadedThreads.clear()
        rebuildNames()
        handledReadyPayload = true
        updateInitializationState()
        markChanged()
    }

    fun handleGuildAdd(guild: Guild) {
        storeGuild(guild)
        markChanged()
    }

    fun handleGuildRemove(guild: Guild) {
        channelsByGuild.remove(guild.id)?.keys?.forEach(channelNames::remove)
        threadsById.values.removeAll { it.guildId == guild.id }
        markChanged()
    }

    fun handleChannelOrThreadCreateOrUpdate(channel: Channel) {
        if (isThread(channel)) {
            storeThread(channel, channel.guildId)
        } else {
            val guildId = if (isPrivate(channel)) PRIVATE_CHANNELS_ID else channel.guildId
            val old = channelsByGuild[guildId]?.get(channel.id)
            val stored = channel.copy(
                guildId = channel.guildId.takeUnless { it == 0L } ?: guildId,
                recipients = channel.recipients ?: old?.recipients,
            )
            channelsByGuild.getOrPut(guildId) { HashMap() }[channel.id] = stored
            channelNames[channel.id] = displayName(stored)
            if (old?.nsfw != stored.nsfw) {
                threadsById.entries.forEach { entry ->
                    if (entry.value.parentId == channel.id) entry.setValue(entry.value.copy(nsfw = stored.nsfw))
                }
            }
        }
        markChanged()
    }

    fun handleThreadCreateOrUpdate(channel: Channel) {
        if (isThread(channel)) handleChannelOrThreadCreateOrUpdate(channel)
    }

    fun handleChannelOrThreadDelete(channel: Channel) {
        val guildId = if (isPrivate(channel)) PRIVATE_CHANNELS_ID else channel.guildId
        channelsByGuild[guildId]?.remove(channel.id)
        channelNames.remove(channel.id)
        threadsById.remove(channel.id)
        markChanged()
    }

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
        privateChannels[channelId] = channel.copy(recipients = recipients, nicks = nicks)
        channelNames[channelId] = displayName(requireNotNull(privateChannels[channelId]))
        markChanged()
    }

    fun handleThreadListSync(guildId: Long, threads: Collection<Channel>) {
        threads.filter(::isThread).forEach { storeThread(it, guildId) }
        if (threads.isNotEmpty()) markChanged()
    }

    fun handleMessagesLoaded(threads: Collection<Channel>) = handleDiscoveredThreads(threads)

    fun handleSearchFinish(threads: Collection<Channel>) = handleDiscoveredThreads(threads)

    fun preloadThread(thread: Channel) { preloadedThreads[thread.id] = thread }

    fun onGroupCreated(channel: Channel) = dispatcher.schedule { handleChannelOrThreadCreateOrUpdate(channel) }

    fun handleStoreInitTimeout() {
        isStoreInitTimedOut = true
        updateInitializationState()
    }

    fun getChannel(channelId: Long): Channel? = channelsByIdSnapshot[channelId] ?: threadsByIdSnapshot[channelId]
    fun findChannelById(channelId: Long): Channel? = threadsById[channelId]
        ?: channelsByGuild.values.firstNotNullOfOrNull { it[channelId] }
    fun getChannelsForGuild(guildId: Long): Map<Long, Channel> = channelsByGuildSnapshot[guildId].orEmpty()
    fun getChannelNames(): Map<Long, String> = channelNamesSnapshot
    fun getInitializedForAuthedUser(): Boolean = initializedForAuthedUser

    internal fun getChannelsByGuildInternal(): Map<Long, Map<Long, Channel>> = channelsByGuild
    internal fun getChannelsByIdInternal(): Map<Long, Channel> = channelsByGuild.values.flatMap { it.values }.associateBy(Channel::id)
    internal fun getThreadsByIdInternal(): Map<Long, Channel> = threadsById
    internal fun getChannelNamesInternal(): Map<Long, String> = channelNames
    internal fun getChannelInternal(guildId: Long, channelId: Long): Channel? =
        channelsByGuild[guildId]?.get(channelId) ?: threadsById[channelId]
    internal fun getGuildChannelInternal(guildId: Long, channelId: Long): Channel? =
        channelsByGuild[guildId]?.get(channelId)
    internal fun findChannelsByCategoryInternal(guildId: Long, categoryId: Long): List<Channel> =
        channelsByGuild[guildId].orEmpty().values.filter { it.parentId == categoryId }
    internal fun getThreadsForChannelInternal(channelId: Long): List<Channel> =
        threadsById.values.filter { it.parentId == channelId }
    internal fun getThreadsForGuildInternal(guildId: Long): List<Channel> =
        threadsById.values.filter { it.guildId == guildId }
    internal fun setInitializedForAuthedUser(initialized: Boolean) { initializedForAuthedUser = initialized }

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
        observePrivateChannels().map { it.values.filter { channel -> channel.type == Channel.DM } }
    fun observeChannelCategories(guildId: Long): Observable<List<Channel>> =
        observeChannelsForGuild(guildId).map { it.values.filter { channel -> channel.type == Channel.CATEGORY } }
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
    fun observeDefaultChannel(guildId: Long): Observable<Channel?> =
        observeChannelPermissionsForGuild(guildId).map { permissions ->
            channelsByGuildSnapshot[guildId].orEmpty().values
                .filter { it.type == Channel.GUILD_TEXT && (permissions[it.id]?.and(VIEW_CHANNEL) ?: 0L) != 0L }
                .minWithOrNull(compareBy<Channel> { it.position }.thenBy { it.id })
        }.distinctUntilChanged()
    fun observeThreadsFromMessages(threadIds: Collection<Long>): Observable<Map<Long, Channel>> =
        observeAllChannels().map { threadsByIdSnapshot.filterKeys(threadIds.toSet()::contains) }.distinctUntilChanged()

    private fun storeGuild(guild: Guild) {
        if (guild.unavailable) return
        val channels = channelsByGuild.getOrPut(guild.id) { HashMap() }
        guild.channels.orEmpty().forEach { channel ->
            val stored = channel.copy(guildId = channel.guildId.takeUnless { it == 0L } ?: guild.id)
            channels[stored.id] = stored
            channelNames[stored.id] = displayName(stored)
        }
        guild.threads.orEmpty().filter(::isThread).forEach { storeThread(it, guild.id) }
    }

    private fun storeThread(thread: Channel, guildId: Long) {
        val resolvedGuildId = thread.guildId.takeUnless { it == 0L } ?: guildId
        val parentNsfw = channelsByGuild[resolvedGuildId]?.get(thread.parentId)?.nsfw ?: thread.nsfw
        val stored = thread.copy(guildId = resolvedGuildId, nsfw = parentNsfw)
        threadsById[stored.id] = stored
        channelNames[stored.id] = displayName(stored)
    }

    private fun handleDiscoveredThreads(threads: Collection<Channel>) {
        var changed = false
        threads.filter(::isThread).forEach { thread ->
            if (thread.id !in threadsById) {
                storeThread(thread, thread.guildId)
                changed = true
            }
            if (!isConnectionOpen) preloadedThreads[thread.id] = thread
        }
        if (changed) markChanged()
    }

    private fun rebuildNames() {
        channelNames.clear()
        channelsByGuild.values.flatMap { it.values }.forEach { channelNames[it.id] = displayName(it) }
        threadsById.values.forEach { channelNames[it.id] = displayName(it) }
    }

    private fun updateInitializationState() {
        val initialized = isAuthenticated() &&
            (channelsByGuild.values.any { it.isNotEmpty() } || handledReadyPayload || isStoreInitTimedOut)
        if (!initializedForAuthedUser && initialized) {
            initializedForAuthedUser = true
            markChanged(InitializedUpdateSource)
        }
    }

    private fun <T> observe(generator: () -> T): Observable<T> = observationDeck.connectRx(this, generator = generator)
    private fun displayName(channel: Channel) = channel.name
        ?: channel.recipients.orEmpty().joinToString(", ") { it.username }
    private fun isPrivate(channel: Channel) = channel.type == Channel.DM || channel.type == Channel.GROUP_DM
    private fun isThread(channel: Channel) = channel.type in setOf(
        Channel.ANNOUNCEMENT_THREAD,
        Channel.PUBLIC_THREAD,
        Channel.PRIVATE_THREAD,
    )

    companion object {
        const val PRIVATE_CHANNELS_ID = 0L
        const val CHANNELS_CACHE_KEY = "STORE_CHANNELS_V26"
        private const val VIEW_CHANNEL = 1L shl 10
        val InitializedUpdateSource = object : ObservationDeck.UpdateSource {}
    }
}
