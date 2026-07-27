package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isThread
import com.discord.api.channel.isVoiceChannel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.message.Message
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreThreadsActiveJoined.ActiveJoinedThread
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/**
 * Read state: which channels and guilds have unread content, the per-channel ack cursor, and the
 * "new messages" marker.
 *
 * The reference splits this across `StoreReadStates` (unread id sets + marker), `StoreMessageAck`
 * (the ack map and the ack round-trip) and `StoreMentions` (mention counts). They are merged here;
 * where a gap against one of those three remains it is called out on the member.
 *
 * Unread is derived, never stored: [recomputeUnreadIds] recomputes both id sets from [inputs] plus
 * [acks] on every relevant dispatch. That is why [updateUnreadInputs] and the thread/voice input
 * handlers exist — the store cannot reach the channel, permission, guild-settings, thread or voice
 * stores itself without a `StoreStream` back-reference.
 */
class StoreReadStates(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck,
    /**
     * Posts "this channel is fully read" to the API. `(channelId, manual, clearLock)`.
     *
     * The caller resolves the message id, because only it knows the channel's most recent message.
     * Used by [markAsRead] only — [markUnread] must ack a *specific, older* message and therefore
     * goes through [postAck].
     */
    private val ackRequest: (Long, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val unreadMessageMarker: Persister<Unread> = Persister(UNREAD_MARKER_CACHE_KEY, Unread()),
    private val acksCache: Persister<Map<Long, Ack>> = Persister(ACKS_CACHE_KEY, emptyMap()),
) : StoreV2() {

    /**
     * The read cursor for one channel.
     *
     * [isLockedAck] is what makes "mark as unread" survive: while it is set, the auto-ack pipeline
     * must not advance the cursor as the user scrolls, otherwise the channel would be re-read the
     * instant it is opened. It is cleared when the user switches channels ([handleChannelSelected]).
     */
    data class Ack(val messageId: Long, val viewed: Boolean = false, val isLockedAck: Boolean = false) {
        constructor(readState: ModelReadState, viewed: Boolean = false, isLockedAck: Boolean = false) :
            this(readState.lastMessageId, viewed, isLockedAck)
    }

    /** The "new messages" divider position in the selected channel, plus how many messages follow it. */
    data class Unread(val marker: Marker = Marker(), val count: Int = 0) {
        data class Marker(val channelId: Long = 0, val messageId: Long? = 0)

        constructor(marker: Marker, messages: List<Message>) : this(marker, getTotalMessages(marker, messages))

        fun createWithEmptyCount() = copy(count = 0)

        companion object {
            /** Messages after [Marker.messageId], counting from the newest backwards. */
            private fun getTotalMessages(marker: Marker, messages: List<Message>): Int {
                val markerMessageId = marker.messageId ?: return 0
                var count = 0
                for (message in messages.asReversed()) {
                    if (message.id == markerMessageId) return count
                    count++
                }
                return count
            }
        }
    }

    /**
     * The per-dispatch projection of the other stores that unread computation reads.
     *
     * Threads and the selected voice channel are deliberately *not* fields here: they arrive from
     * stores with a different update cadence and are pushed through [updateActiveThreads] and
     * [updateSelectedVoiceChannel]. Folding them in would mean every caller of
     * [updateUnreadInputs] had to know about them, and a caller that did not would silently reset
     * them to empty — which is exactly the defect this split removes.
     */
    data class Inputs(
        val channelPermissions: Map<Long, Long> = emptyMap(),
        val channels: Map<Long, Channel> = emptyMap(),
        val guildJoinedAt: Map<Long, Long> = emptyMap(),
        val guildSettings: Map<Long, ModelNotificationSettings> = emptyMap(),
        val recentMessageIds: Map<Long, Long> = emptyMap(),
        val channelGuildIds: Map<Long, Long> = emptyMap(),
    )

    private val readStates = HashMap<Long, ModelReadState>()
    private val acks = HashMap<Long, Ack>()
    private var inputs = Inputs()

    /** Threads this user has joined, keyed by thread id. Fed by `StoreThreadsActiveJoined`. */
    private var activeJoinedThreads: Map<Long, ActiveJoinedThread> = emptyMap()

    /** Active forum posts, keyed by thread id. Fed by `StoreThreadsActive`. */
    private var activeForumThreads: Map<Long, Channel> = emptyMap()

    /** Fed by `StoreVoiceChannelSelected`; text in a voice channel is only unread while you are in it. */
    private var selectedVoiceChannelId: Long = 0
    private var selectedVoiceChannelAt: Long = 0

    private var unreadChannelIds = emptySet<Long>()
    private var unreadGuildIds = emptySet<Long>()
    private var mentionCountsByChannel = emptyMap<Long, Int>()
    private var mentionCountsByGuild = emptyMap<Long, Int>()
    private var readStatesSnapshot: Map<Long, ModelReadState> = emptyMap()
    private var acksSnapshot: Map<Long, Ack> = emptyMap()
    private var unreadChannelIdsSnapshot = emptySet<Long>()
    private var unreadGuildIdsSnapshot = emptySet<Long>()
    private var mentionCountsByChannelSnapshot = emptyMap<Long, Int>()
    private var mentionCountsByGuildSnapshot = emptyMap<Long, Int>()
    private var unreadMarker = Unread()
    private var unreadMarkerSnapshot = Unread()
    private val markAsReadSubject = SerializedSubject(BehaviorSubject.create(false))

    /**
     * Bound once by `StoreStream` after the REST stack exists.
     *
     * Only [markUnread] needs it: it must post an ack for a message the caller chose, which the
     * `ackRequest` contract cannot express. While unbound, mark-unread updates local state only —
     * it must never fall back to `ackRequest`, because that would tell the server the channel is
     * fully read, the exact inversion this store used to ship with.
     */
    private var api: RestAPIInterface? = null

    /** Loaded message ids for a channel, newest last. Wired from `StoreMessages`. */
    private var getLoadedMessageIds: (Long) -> List<Long> = { emptyList() }

    fun bindApi(api: RestAPIInterface) {
        this.api = api
    }

    fun bindMessageSource(getLoadedMessageIds: (Long) -> List<Long>) {
        this.getLoadedMessageIds = getLoadedMessageIds
    }

    // -- Lifecycle ------------------------------------------------------------------------------

    @StoreThread
    override fun init(context: Context) = restore()

    @StoreThread
    internal fun restore() {
        acks.putAll(acksCache.get())
        unreadMarker = unreadMessageMarker.get()
        recomputeUnreadIds()
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() = Unit

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        readStates.clear()
        acks.clear()
        inputs = Inputs()
        activeJoinedThreads = emptyMap()
        activeForumThreads = emptyMap()
        selectedVoiceChannelId = 0
        selectedVoiceChannelAt = 0
        unreadChannelIds = emptySet()
        unreadGuildIds = emptySet()
        mentionCountsByChannel = emptyMap()
        mentionCountsByGuild = emptyMap()
        unreadMarker = Unread()
        unreadMessageMarker.clear(persist = true)
        acksCache.clear(persist = true)
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        readStatesSnapshot = readStates.toMap()
        acksSnapshot = acks.toMap()
        unreadChannelIdsSnapshot = unreadChannelIds.toSet()
        unreadGuildIdsSnapshot = unreadGuildIds.toSet()
        mentionCountsByChannelSnapshot = mentionCountsByChannel.toMap()
        mentionCountsByGuildSnapshot = mentionCountsByGuild.toMap()
        unreadMarkerSnapshot = unreadMarker
        unreadMessageMarker.set(unreadMarkerSnapshot)
        acksCache.set(acksSnapshot)
    }

    // -- Reads ----------------------------------------------------------------------------------

    fun getReadStates(): Map<Long, ModelReadState> = readStatesSnapshot
    fun getAllAcks(): Map<Long, Ack> = acksSnapshot
    fun getAck(channelId: Long): Ack? = acksSnapshot[channelId]
    fun getUnreadChannelIdsSnapshot(): Set<Long> = unreadChannelIdsSnapshot
    fun getUnreadGuildIdsSnapshot(): Set<Long> = unreadGuildIdsSnapshot
    fun getMentionCountsByChannelSnapshot(): Map<Long, Int> = mentionCountsByChannelSnapshot
    fun getMentionCountsByGuildSnapshot(): Map<Long, Int> = mentionCountsByGuildSnapshot
    fun getUnreadMarkerSnapshot(): Unread = unreadMarkerSnapshot

    @StoreThread
    internal fun getAllAcksInternal(): Map<Long, Ack> = acks

    fun getUnreadChannelIds(): Observable<Set<Long>> =
        observationDeck.connectRx(this, generator = ::getUnreadChannelIdsSnapshot).distinctUntilChanged()
    fun getUnreadGuildIds(): Observable<Set<Long>> =
        observationDeck.connectRx(this, generator = ::getUnreadGuildIdsSnapshot).distinctUntilChanged()
    fun getMentionCountsByChannel(): Observable<Map<Long, Int>> =
        observationDeck.connectRx(this, generator = ::getMentionCountsByChannelSnapshot).distinctUntilChanged()
    fun getMentionCountsByGuild(): Observable<Map<Long, Int>> =
        observationDeck.connectRx(this, generator = ::getMentionCountsByGuildSnapshot).distinctUntilChanged()
    fun getIsUnread(guildId: Long): Observable<Boolean> =
        getUnreadGuildIds().map { guildId in it }.distinctUntilChanged()
    fun observeAllAcks(): Observable<Map<Long, Ack>> =
        observationDeck.connectRx(this, generator = ::getAllAcks).distinctUntilChanged()
    fun observeAck(channelId: Long): Observable<Ack?> =
        observeAllAcks().map { it[channelId] }.distinctUntilChanged()
    fun observeMarkAsRead(): Observable<Boolean> = markAsReadSubject.distinctUntilChanged()
    fun getUnreadMarkerForSelectedChannel(): Observable<Unread> =
        observationDeck.connectRx(this, generator = ::getUnreadMarkerSnapshot).distinctUntilChanged()

    /**
     * The marker, but only while it belongs to [channelId].
     *
     * Filtered rather than mapped: emitting an empty [Unread] for other channels would make every
     * chat list clear its divider whenever a different channel's marker moved.
     */
    fun getUnreadMarker(channelId: Long): Observable<Unread> = getUnreadMarkerForSelectedChannel()
        .filter { it.marker.channelId == channelId }.distinctUntilChanged()

    /**
     * Messages loaded after the ack cursor, optionally restricted to messages newer than [since].
     *
     * Counts real loaded messages when a message source is bound; without one it degrades to a
     * 0/1 "has unread" signal.
     */
    fun observeUnreadCountForChannel(channelId: Long, since: UtcDateTime? = null): Observable<Int> =
        observationDeck.connectRx(this, generator = {
            val ack = acksSnapshot[channelId]?.messageId
            val loaded = getLoadedMessageIds(channelId)
            if (loaded.isNotEmpty()) {
                var count = 0
                for (messageId in loaded.asReversed()) {
                    if (ack != null && messageId == ack) break
                    if (wasMessageSentAfterTimestamp(since, messageId)) count++
                }
                count
            } else {
                val recent = inputs.recentMessageIds[channelId]
                if (recent != null && isNewer(recent, ack) && wasMessageSentAfterTimestamp(since, recent)) 1 else 0
            }
        }).distinctUntilChanged()

    // -- Gateway handlers -----------------------------------------------------------------------

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

    /**
     * Release every mark-as-unread lock.
     *
     * The lock only has to hold until the user navigates away; keeping it would leave the channel
     * permanently unreadable-away, since the auto-ack pipeline would never advance again.
     */
    @StoreThread
    fun handleChannelSelected() {
        var changed = false
        acks.entries.forEach { entry ->
            if (entry.value.isLockedAck) {
                entry.setValue(entry.value.copy(isLockedAck = false))
                changed = true
            }
        }
        if (changed) markChanged()
    }

    // -- Inputs ---------------------------------------------------------------------------------

    @StoreThread
    fun updateUnreadInputs(inputs: Inputs) {
        StoreThreadScheduler.assertCurrentThread()
        this.inputs = inputs
        recomputeUnreadIds()
    }

    /**
     * Thread membership, which decides whether a thread can be unread at all.
     *
     * A non-forum thread is only unread when the user joined it; a forum post is only unread while
     * it is an active post. Both maps therefore have to be present or every thread is discarded.
     */
    @StoreThread
    fun updateActiveThreads(
        activeJoinedThreads: Map<Long, ActiveJoinedThread>,
        activeForumThreads: Map<Long, Channel>,
    ) {
        StoreThreadScheduler.assertCurrentThread()
        this.activeJoinedThreads = activeJoinedThreads
        this.activeForumThreads = activeForumThreads
        recomputeUnreadIds()
    }

    /**
     * @param timeSelectedMs when the current voice channel was joined; messages older than that do
     *   not count as unread, so joining a busy voice channel does not immediately show a badge.
     */
    @StoreThread
    fun updateSelectedVoiceChannel(channelId: Long, timeSelectedMs: Long) {
        StoreThreadScheduler.assertCurrentThread()
        selectedVoiceChannelId = channelId
        selectedVoiceChannelAt = timeSelectedMs
        recomputeUnreadIds()
    }

    @StoreThread
    fun updateUnreadMarker(marker: Unread.Marker, count: Int) {
        StoreThreadScheduler.assertCurrentThread()
        unreadMarker = Unread(marker, count)
        markChanged()
    }

    // -- Commands -------------------------------------------------------------------------------

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

    /**
     * Mark the channel unread starting at [messageId].
     *
     * The ack has to land on the newest loaded message *older* than [messageId] — acking
     * [messageId] itself would mark it read. When nothing older is loaded the reference synthesises
     * a cursor from the message's own timestamp; that expression is reproduced verbatim below
     * including its double epoch subtraction, which yields a cursor older than every real
     * snowflake and therefore marks the whole channel unread.
     *
     * The resulting ack is locked so the auto-ack pipeline cannot immediately undo it.
     */
    fun markUnread(channelId: Long, messageId: Long) {
        dispatcher.schedule {
            val loaded = getLoadedMessageIds(channelId)
            val newestOlder = loaded.filter { it < messageId }.maxOrNull()
            // Reproduces a reference bug verbatim: `atPreviousMillisecond` subtracts the epoch
            // from a value that is already epoch-relative, so this fallback bound is a large
            // negative id, not "one millisecond before the message". Verified at bytecode level
            // (`javap -c` of `SnowflakeUtils`); the intended value is
            // `fromTimestamp(toTimestamp(messageId) - 1)`. Not fixed here — see
            // `docs/parity-audit/followups.md`, "Reference bugs reproduced verbatim".
            val ackMessageId = newestOlder ?: SnowflakeUtils.atPreviousMillisecond(messageId)
            val ack = Ack(ackMessageId, viewed = false, isLockedAck = true)
            updateAck(channelId, ack)
            val mentionCount = loaded.count { it >= messageId }
            postAck(channelId, ack, mentionCount)
            recomputeUnreadIds()
        }
    }

    /**
     * `POST /channels/{id}/messages/{messageId}/ack`.
     *
     * `manual` carries the locked flag, matching the reference body.
     */
    @StoreThread
    private fun postAck(channelId: Long, ack: Ack, mentionCount: Int?) {
        val api = api ?: return
        api.postChannelMessagesAck(channelId, ack.messageId, RestAPIParams.ChannelMessagesAck(ack.isLockedAck, mentionCount))
            .restSubscribeOn()
            .appSubscribe(StoreReadStates::class.java) { }
    }

    // -- Internals -------------------------------------------------------------------------------

    @StoreThread
    private fun putReadState(readState: ModelReadState) {
        val old = readStates[readState.channelId]
        if (old == readState) return
        readStates[readState.channelId] = readState
        updateAck(readState.channelId, Ack(readState))
        markChanged()
    }

    /**
     * Advance the cursor.
     *
     * A same-position write still counts as a change when it *adds* the lock, which is how
     * mark-unread on an already-acked message takes effect.
     */
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
        val channelMentions = readStates.values.filter { it.mentionCount > 0 }
            .associate { it.channelId to it.mentionCount }
        val guildMentions = HashMap<Long, Int>()
        channelMentions.forEach { (channelId, count) ->
            resolveGuildId(channelId)?.let { guildId -> guildMentions[guildId] = (guildMentions[guildId] ?: 0) + count }
        }
        inputs.recentMessageIds.forEach { (channelId, recentMessageId) ->
            val joined = activeJoinedThreads[channelId]
            val activeForumThread = activeForumThreads[channelId]
            val channel = inputs.channels[channelId] ?: joined?.channel ?: activeForumThread ?: return@forEach
            val parent = inputs.channels[channel.parentId]
            val forumPost = channel.isThread() && channel.isGuildForumPost(parent)
            val privateChannel = channel.isPrivate()
            if (channel.isThread() && !forumPost && joined == null) return@forEach
            if (channel.isThread() && forumPost && activeForumThread == null) return@forEach
            if (!PermissionUtils.hasAccess(channel, inputs.channelPermissions[channelId])) return@forEach
            if (!privateChannel && isPlainUnreadMuted(channel, joined)) return@forEach
            if (channel.isVoiceChannel() && channel.id != selectedVoiceChannelId) return@forEach
            if (channel.isVoiceChannel() && channel.id == selectedVoiceChannelId &&
                !wasMessageSentAfterTimestamp(UtcDateTime(selectedVoiceChannelAt), recentMessageId)
            ) return@forEach

            val ackMessageId = acks[channelId]?.messageId
                ?: if (channel.isThread()) acks[channel.parentId]?.messageId else null
                ?: if (privateChannel) null else inputs.guildJoinedAt[channel.guildId]?.let(::timestampToSnowflake)
                ?: if (privateChannel) null else timestampToSnowflake(clock.currentTimeMillis())
            if (!isNewer(recentMessageId, ackMessageId)) return@forEach
            unreadChannels += channelId
            if (privateChannel) return@forEach

            var muted = isChannelMuted(parent, joined)
            if (!muted && channel.isThread()) muted = isChannelMuted(inputs.channels[parent?.parentId], joined)
            if (!muted) unreadGuilds += resolveGuildId(channelId) ?: channel.guildId
        }
        if (unreadChannels != unreadChannelIds || unreadGuilds != unreadGuildIds ||
            channelMentions != mentionCountsByChannel || guildMentions != mentionCountsByGuild
        ) {
            unreadChannelIds = unreadChannels
            unreadGuildIds = unreadGuilds
            mentionCountsByChannel = channelMentions
            mentionCountsByGuild = guildMentions
            markChanged()
        }
    }

    private fun resolveGuildId(channelId: Long): Long? {
        inputs.channelGuildIds[channelId]?.let { return it }
        var channel = inputs.channels[channelId] ?: activeJoinedThreads[channelId]?.channel
            ?: activeForumThreads[channelId]
        repeat(3) {
            channel ?: return null
            if (channel!!.guildId != 0L) return channel!!.guildId
            channel = inputs.channels[channel!!.parentId]
        }
        return null
    }

    private fun isPlainUnreadMuted(channel: Channel, joined: ActiveJoinedThread?): Boolean {
        val guildId = resolveGuildId(channel.id) ?: channel.guildId
        val settings = inputs.guildSettings[guildId]
        if (settings?.isMuted == true || isChannelMuted(channel, joined)) return true
        var parentId = channel.parentId
        repeat(2) {
            val parent = inputs.channels[parentId] ?: return false
            if (settings?.getChannelOverride(parent.id)?.isMuted == true) return true
            parentId = parent.parentId
        }
        return false
    }

    private fun isChannelMuted(channel: Channel?, joined: ActiveJoinedThread?): Boolean {
        if (channel == null) return false
        if (channel.isThread() && joined != null) return joined.muted
        return inputs.guildSettings[channel.guildId]?.getChannelOverride(channel.id)?.isMuted == true
    }

    @StoreThread
    private fun clearMarker() {
        unreadMarker = unreadMarker.createWithEmptyCount()
        markChanged()
    }

    private fun wasMessageSentAfterTimestamp(timestamp: UtcDateTime?, messageId: Long?): Boolean =
        timestamp == null || messageId == null || snowflakeTimestamp(messageId) >= timestamp.dateTimeMillis

    private fun isNewer(messageId: Long, other: Long?) = other == null || messageId > other
    // Clamping the timestamp rather than the result keeps this identical to the open-coded
    // `(t - EPOCH).coerceAtLeast(0) shl 22`: below the epoch both yield 0.
    private fun timestampToSnowflake(timestamp: Long) =
        SnowflakeUtils.fromTimestamp(timestamp.coerceAtLeast(SnowflakeUtils.DISCORD_EPOCH))
    private fun snowflakeTimestamp(id: Long) = SnowflakeUtils.toTimestamp(id)

    companion object {
        const val UNREAD_MARKER_CACHE_KEY = "UNREAD_MESSAGE_MARKER_V3"
        const val ACKS_CACHE_KEY = "MOST_RECENT_ACKS_V3"
    }
}
