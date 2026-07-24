package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

class StoreReadStates(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck,
    private val ackRequest: (Long, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val unreadMessageMarker: Persister<Unread> = Persister(UNREAD_MARKER_CACHE_KEY, Unread()),
    private val acksCache: Persister<Map<Long, Ack>> = Persister(ACKS_CACHE_KEY, emptyMap()),
) : StoreV2() {
    data class Ack(val messageId: Long, val isLockedAck: Boolean = false, val viewed: Boolean = false) {
        constructor(readState: ModelReadState, isLockedAck: Boolean = false, viewed: Boolean = false) :
            this(readState.lastMessageId, isLockedAck, viewed)
    }

    data class Unread(val marker: Marker = Marker(), val count: Int = 0) {
        data class Marker(val channelId: Long = 0, val messageId: Long? = 0)
        fun createWithEmptyCount() = copy(count = 0)
    }

    data class ActiveJoinedThread(val channel: Channel, val muted: Boolean)

    data class Inputs(
        val channelPermissions: Map<Long, Long> = emptyMap(),
        val channels: Map<Long, Channel> = emptyMap(),
        val guildJoinedAt: Map<Long, Long> = emptyMap(),
        val guildSettings: Map<Long, ModelNotificationSettings> = emptyMap(),
        val recentMessageIds: Map<Long, Long> = emptyMap(),
        val selectedVoiceChannelId: Long = 0,
        val selectedVoiceChannelAt: Long = 0,
        val activeForumThreads: Map<Long, Channel> = emptyMap(),
        val activeJoinedThreads: Map<Long, ActiveJoinedThread> = emptyMap(),
    )

    private val readStates = HashMap<Long, ModelReadState>()
    private val acks = HashMap<Long, Ack>()
    private var inputs = Inputs()
    private var unreadChannelIds = emptySet<Long>()
    private var unreadGuildIds = emptySet<Long>()
    private var readStatesSnapshot: Map<Long, ModelReadState> = emptyMap()
    private var acksSnapshot: Map<Long, Ack> = emptyMap()
    private var unreadChannelIdsSnapshot = emptySet<Long>()
    private var unreadGuildIdsSnapshot = emptySet<Long>()
    private var unreadMarker = Unread()
    private var unreadMarkerSnapshot = Unread()
    private val markAsReadSubject = SerializedSubject(BehaviorSubject.create(false))

    override fun init(context: Context) = restore()

    internal fun restore() {
        acks.putAll(acksCache.get())
        unreadMarker = unreadMessageMarker.get()
        recomputeUnreadIds()
        markChanged()
    }

    fun getReadStates(): Map<Long, ModelReadState> = readStatesSnapshot
    fun getAllAcks(): Map<Long, Ack> = acksSnapshot
    fun getAck(channelId: Long): Ack? = acksSnapshot[channelId]
    fun getUnreadChannelIdsSnapshot(): Set<Long> = unreadChannelIdsSnapshot
    fun getUnreadGuildIdsSnapshot(): Set<Long> = unreadGuildIdsSnapshot
    fun getUnreadMarkerSnapshot(): Unread = unreadMarkerSnapshot

    fun getUnreadChannelIds(): Observable<Set<Long>> =
        observationDeck.connectRx(this, generator = ::getUnreadChannelIdsSnapshot).distinctUntilChanged()
    fun getUnreadGuildIds(): Observable<Set<Long>> =
        observationDeck.connectRx(this, generator = ::getUnreadGuildIdsSnapshot).distinctUntilChanged()
    fun getIsUnread(guildId: Long): Observable<Boolean> =
        getUnreadGuildIds().map { guildId in it }.distinctUntilChanged()
    fun observeAllAcks(): Observable<Map<Long, Ack>> =
        observationDeck.connectRx(this, generator = ::getAllAcks).distinctUntilChanged()
    fun observeAck(channelId: Long): Observable<Ack?> =
        observeAllAcks().map { it[channelId] }.distinctUntilChanged()
    fun observeMarkAsRead(): Observable<Boolean> = markAsReadSubject.distinctUntilChanged()
    fun getUnreadMarkerForSelectedChannel(): Observable<Unread> =
        observationDeck.connectRx(this, generator = ::getUnreadMarkerSnapshot).distinctUntilChanged()
    fun getUnreadMarker(channelId: Long): Observable<Unread> = getUnreadMarkerForSelectedChannel()
        .map { if (it.marker.channelId == channelId) it else Unread() }.distinctUntilChanged()

    fun observeUnreadCountForChannel(channelId: Long, since: UtcDateTime? = null): Observable<Int> =
        observationDeck.connectRx(this, generator = {
            val ack = acksSnapshot[channelId]?.messageId
            val recent = inputs.recentMessageIds[channelId]
            if (recent != null && isNewer(recent, ack) && wasMessageSentAfterTimestamp(since, recent)) 1 else 0
        }).distinctUntilChanged()

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        val versioned = payload.readState ?: return
        if (!versioned.isPartial) {
            readStates.clear()
            acks.clear()
        }
        versioned.entries.orEmpty().forEach(::putReadState)
        recomputeUnreadIds()
        markChanged()
    }

    @StoreThread
    fun handleMessageAck(readState: ModelReadState) {
        putReadState(readState)
        recomputeUnreadIds()
    }

    @StoreThread
    fun handleChannelUnreadUpdate(readStates: List<ModelReadState>) {
        readStates.forEach(::putReadState)
        recomputeUnreadIds()
    }

    @StoreThread
    fun updateUnreadInputs(inputs: Inputs) {
        StoreThreadScheduler.assertCurrentThread()
        this.inputs = inputs
        recomputeUnreadIds()
    }

    @StoreThread
    fun updateUnreadMarker(marker: Unread.Marker, count: Int) {
        StoreThreadScheduler.assertCurrentThread()
        unreadMarker = Unread(marker, count)
        markChanged()
    }

    fun markAsRead(channelId: Long?) {
        if (channelId == null) return
        markAsReadSubject.onNext(true)
        markAsReadSubject.onNext(false)
        ackRequest(channelId, false, false)
        dispatcher.schedule {
            val messageId = inputs.recentMessageIds[channelId] ?: acks[channelId]?.messageId ?: 0L
            updateAck(channelId, Ack(messageId))
            if (unreadMarker.marker.channelId == channelId) clearMarker()
            recomputeUnreadIds()
        }
    }

    fun markUnread(channelId: Long, messageId: Long) {
        ackRequest(channelId, false, false)
        dispatcher.schedule {
            updateAck(channelId, Ack(messageId))
            recomputeUnreadIds()
        }
    }

    @StoreThread
    fun handlePreLogout() = Unit

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        readStates.clear()
        acks.clear()
        inputs = Inputs()
        unreadChannelIds = emptySet()
        unreadGuildIds = emptySet()
        unreadMarker = Unread()
        unreadMessageMarker.clear(persist = true)
        acksCache.clear(persist = true)
        markChanged()
    }

    override fun snapshotData() {
        readStatesSnapshot = readStates.toMap()
        acksSnapshot = acks.toMap()
        unreadChannelIdsSnapshot = unreadChannelIds.toSet()
        unreadGuildIdsSnapshot = unreadGuildIds.toSet()
        unreadMarkerSnapshot = unreadMarker
        unreadMessageMarker.set(unreadMarkerSnapshot, persist = true)
        acksCache.set(acksSnapshot)
    }

    @StoreThread
    private fun putReadState(readState: ModelReadState) {
        val old = readStates[readState.channelId]
        if (old == readState) return
        readStates[readState.channelId] = readState
        updateAck(readState.channelId, Ack(readState))
        markChanged()
    }

    @StoreThread
    private fun updateAck(channelId: Long, ack: Ack) {
        val old = acks[channelId]
        if (old?.messageId == ack.messageId && (!ack.isLockedAck || old.isLockedAck)) return
        acks[channelId] = ack
        markChanged()
    }

    @StoreThread
    private fun recomputeUnreadIds() {
        StoreThreadScheduler.assertCurrentThread()
        val unreadChannels = HashSet<Long>()
        val unreadGuilds = HashSet<Long>()
        inputs.recentMessageIds.forEach { (channelId, recentMessageId) ->
            val joined = inputs.activeJoinedThreads[channelId]
            val activeForumThread = inputs.activeForumThreads[channelId]
            val channel = inputs.channels[channelId] ?: joined?.channel ?: activeForumThread ?: return@forEach
            val parent = inputs.channels[channel.parentId]
            val forumPost = channel.isThread() && parent?.type == Channel.GUILD_FORUM
            if (channel.type == Channel.DM || channel.type == Channel.GROUP_DM) return@forEach
            if (channel.isThread() && !forumPost && joined == null) return@forEach
            if (channel.isThread() && forumPost && activeForumThread == null) return@forEach
            if ((inputs.channelPermissions[channelId] ?: 0L) and VIEW_CHANNEL == 0L) return@forEach
            if (isChannelMuted(channel, joined)) return@forEach
            if (channel.type == Channel.GUILD_VOICE && channel.id != inputs.selectedVoiceChannelId) return@forEach
            if (channel.type == Channel.GUILD_VOICE && channel.id == inputs.selectedVoiceChannelId &&
                !wasMessageSentAfterTimestamp(UtcDateTime(inputs.selectedVoiceChannelAt), recentMessageId)) return@forEach

            val ackMessageId = acks[channelId]?.messageId
                ?: if (channel.isThread()) acks[channel.parentId]?.messageId else null
                ?: inputs.guildJoinedAt[channel.guildId]?.let(::timestampToSnowflake)
                ?: timestampToSnowflake(clock.currentTimeMillis())
            if (!isNewer(recentMessageId, ackMessageId)) return@forEach
            unreadChannels += channelId

            var muted = isChannelMuted(parent, joined)
            if (!muted && channel.isThread()) muted = isChannelMuted(inputs.channels[parent?.parentId], joined)
            if (!muted) unreadGuilds += channel.guildId
        }
        if (unreadChannels != unreadChannelIds || unreadGuilds != unreadGuildIds) {
            unreadChannelIds = unreadChannels
            unreadGuildIds = unreadGuilds
            markChanged()
        }
    }

    private fun isChannelMuted(channel: Channel?, joined: ActiveJoinedThread?): Boolean {
        if (channel == null) return false
        if (channel.isThread() && joined != null) return joined.muted
        return inputs.guildSettings[channel.guildId]?.getChannelOverride(channel.id)?.isMuted == true
    }

    private fun clearMarker() {
        unreadMarker = unreadMarker.createWithEmptyCount()
        markChanged()
    }

    private fun wasMessageSentAfterTimestamp(timestamp: UtcDateTime?, messageId: Long?): Boolean =
        timestamp == null || messageId == null || snowflakeTimestamp(messageId) >= timestamp.dateTimeMillis

    private fun isNewer(messageId: Long, other: Long?) = other == null || messageId > other
    private fun timestampToSnowflake(timestamp: Long) = (timestamp - DISCORD_EPOCH).coerceAtLeast(0) shl 22
    private fun snowflakeTimestamp(id: Long) = (id ushr 22) + DISCORD_EPOCH
    private fun Channel.isThread() = type in Channel.ANNOUNCEMENT_THREAD..Channel.PRIVATE_THREAD

    companion object {
        const val UNREAD_MARKER_CACHE_KEY = "UNREAD_MESSAGE_MARKER_V3"
        const val ACKS_CACHE_KEY = "MOST_RECENT_ACKS_V3"
        private const val VIEW_CHANNEL = 1024L
        private const val DISCORD_EPOCH = 1_420_070_400_000L
    }
}
