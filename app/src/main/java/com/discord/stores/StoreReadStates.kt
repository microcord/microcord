package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isThread
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.application.Unread
import com.discord.models.domain.ModelNotificationSettings
import com.discord.stores.StoreMessageAck.Ack
import com.discord.stores.StoreThreadsActiveJoined.ActiveJoinedThread
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/** Reactive projection of the authoritative [StoreMessageAck] ledger into unread channel state. */
class StoreReadStates(private val clock: Clock) : Store() {
    private val unreadChannelIds = SerializedSubject(BehaviorSubject.create<Set<Long>>(emptySet()))
    private val unreadGuildIds = SerializedSubject(BehaviorSubject.create<Set<Long>>(emptySet()))
    private val markAsRead = SerializedSubject(BehaviorSubject.create(false))
    private val unreadMessageMarker = Persister(UNREAD_MARKER_CACHE_KEY, Unread())

    override fun init(context: Context) {
        super.init(context)
        start()
    }

    internal fun initForTesting() {
        start()
    }

    private fun start() {
        computeUnreadChannelIds()
        computeUnreadMarker()
    }

    fun getUnreadChannelIds(): Observable<Set<Long>> = unreadChannelIds

    fun getUnreadGuildIds(): Observable<Set<Long>> = unreadGuildIds

    fun getIsUnread(guildId: Long): Observable<Boolean> =
        unreadGuildIds.map { guildId in it }.distinctUntilChanged()

    fun getUnreadMarkerForSelectedChannel(): Observable<Unread> =
        unreadMessageMarker.getObservable().computationLatest().distinctUntilChanged()

    fun getUnreadMarker(channelId: Long): Observable<Unread> =
        getUnreadMarkerForSelectedChannel()
            .filter { it.marker.channelId == channelId }
            .distinctUntilChanged()

    fun markAsRead(channelId: Long?) {
        if (channelId == null) return
        markAsRead.onNext(true)
        markAsRead.onNext(false)
        StoreStream.getMessageAck().ack(channelId, false, false)
    }

    fun observeUnreadCountForChannel(channelId: Long, since: UtcDateTime? = null): Observable<Int> =
        Observable.combineLatest(
            StoreStream.getMessages().observeMessagesForChannel(channelId),
            StoreStream.getMessageAck().observeAll().map { it[channelId] },
        ) { messages, ack ->
            var count = 0
            for (message in messages.asReversed()) {
                if (ack != null && message.id == ack.messageId) break
                if (wasMessageSentAfterTimestamp(since, message.id)) count++
            }
            count
        }

    private fun computeUnreadChannelIds() {
        val stream = StoreStream
        val inputs = listOf<Observable<*>>(
            stream.getPermissions().observePermissionsForAllChannels(),
            stream.getChannels().observeGuildAndPrivateChannels(),
            stream.getGuilds().observeJoinedAt(),
            stream.getUserGuildSettings().observeGuildSettings(),
            stream.getMessageAck().observeAll(),
            stream.getMessagesMostRecent().observeRecentMessageIds().leadingEdgeThrottle(3, TimeUnit.SECONDS),
            stream.getVoiceChannelSelected().observeSelectedVoiceChannelId(),
            stream.getThreadsActive().observeAllActiveForumThreadsById().leadingEdgeThrottle(3, TimeUnit.SECONDS),
            stream.getThreadsActiveJoined().observeAllActiveJoinedThreadsById(),
        )
        Observable.combineLatest(inputs) { values ->
            @Suppress("UNCHECKED_CAST")
            computeUnreadIds(
                values[0] as Map<Long, Long>,
                values[1] as Map<Long, Channel>,
                values[2] as Map<Long, Long>,
                values[3] as Map<Long, ModelNotificationSettings>,
                values[4] as Map<Long, Ack>,
                values[5] as Map<Long, Long>,
                values[6] as Long,
                values[7] as Map<Long, Channel>,
                values[8] as Map<Long, ActiveJoinedThread>,
            )
        }
            .leadingEdgeThrottle(1, TimeUnit.SECONDS)
            .computationLatest()
            .distinctUntilChanged()
            .subscribe({ (channels, guilds) ->
                unreadChannelIds.onNext(channels)
                unreadGuildIds.onNext(guilds)
            }, { })
    }

    private fun computeUnreadMarker() {
        StoreStream.getChannelsSelected().observeId()
            .switchMap { channelId ->
                if (channelId <= 0L) {
                    Observable.just(Unread.Marker())
                } else {
                    Observable.combineLatest(
                        StoreStream.getMessageAck().observeForChannel(channelId).take(1),
                        StoreStream.getMessageAck().observeForChannel(channelId),
                        StoreStream.getMessagesMostRecent().observeRecentMessageIds(channelId).take(1),
                    ) { ackAtSwitch, liveAck, mostRecentMessageId ->
                        markerFor(channelId, ackAtSwitch, liveAck, mostRecentMessageId)
                    }
                }
            }
            .switchMap { marker ->
                StoreStream.getMessages().observeMessagesForChannel(marker.channelId)
                    .map { Unread(marker, it) }
                    .takeUntil(markAsRead.filter { it })
                    .doOnUnsubscribe(::clearMarker)
            }
            .subscribe({ unreadMessageMarker.set(it) }, { })
    }

    private fun clearMarker() {
        unreadMessageMarker.getObservable()
            .map(Unread::createWithEmptyCount)
            .take(1)
            .subscribe({ unreadMessageMarker.set(it) }, { })
    }

    internal fun computeUnreadIds(
        permissions: Map<Long, Long>,
        channels: Map<Long, Channel>,
        guildJoinedAt: Map<Long, Long>,
        guildSettings: Map<Long, ModelNotificationSettings>,
        acks: Map<Long, Ack>,
        mostRecent: Map<Long, Long>,
        selectedVoiceChannelId: Long,
        activeForumThreads: Map<Long, Channel>,
        activeJoinedThreads: Map<Long, ActiveJoinedThread>,
    ): Pair<Set<Long>, Set<Long>> {
        val unreadChannels = HashSet<Long>()
        val unreadGuilds = HashSet<Long>()
        for ((channelId, mostRecentMessageId) in mostRecent) {
            val joinedThread = activeJoinedThreads[channelId]
            val activeForumThread = activeForumThreads[channelId]
            val channel = channels[channelId] ?: joinedThread?.channel ?: activeForumThread ?: continue
            val parent = channels[channel.parentId]
            val forumPost = channel.isGuildForumPost(parent)
            if (channel.isPrivate() || channel.isThread() && !forumPost && joinedThread == null) continue
            if (channel.isThread() && forumPost && activeForumThread == null) continue
            if (!PermissionUtils.hasAccess(channel, permissions[channelId])) continue
            if (isChannelMuted(channel, guildSettings, joinedThread)) continue
            // A vocal channel's chat only shows a dot while you are in it: the row is otherwise
            // showing who is connected, and a permanent dot there reads as activity in the call.
            if (channel.isGuildVocalChannel() && channel.id != selectedVoiceChannelId) continue
            if (channel.isGuildVocalChannel() && !wasMessageSentAfterTimestamp(
                    UtcDateTime(StoreStream.getVoiceChannelSelected().getTimeSelectedMs()),
                    mostRecentMessageId,
                )
            ) continue

            val ackMessageId = acks[channelId]?.messageId
                ?: StoreStream.getMessageAck().getAckMessageId(channel, guildJoinedAt[channel.guildId])
            if (!MessageUtils.isNewer(ackMessageId, mostRecentMessageId)) continue

            unreadChannels += channelId
            // A plain unread in a vocal channel never reaches the guild pill — only a mention
            // does, and mentions are counted separately. Chat in a call is constant and would
            // otherwise leave every server the viewer talks in permanently marked unread.
            if (channel.isGuildVocalChannel()) continue
            // An unjoined forum post is unread in the browser but invisible everywhere else, so
            // the reference rolls up only threads the viewer joined. Without this a forum the
            // viewer never opens keeps its server permanently marked.
            if (channel.isThread() && joinedThread == null) continue
            var parentMuted = isChannelMuted(parent, guildSettings, joinedThread)
            if (!parentMuted && channel.isThread() && parent != null) {
                parentMuted = isChannelMuted(channels[parent.parentId], guildSettings, joinedThread)
            }
            if (!parentMuted) unreadGuilds += channel.guildId
        }
        return unreadChannels to unreadGuilds
    }

    private fun isChannelMuted(
        channel: Channel?,
        guildSettings: Map<Long, ModelNotificationSettings>,
        joinedThread: ActiveJoinedThread?,
    ): Boolean {
        if (channel == null) return false
        if (channel.isThread() && joinedThread != null) return joinedThread.muted
        return guildSettings[channel.guildId]?.getChannelOverride(channel.id)?.isMuted == true
    }

    private fun wasMessageSentAfterTimestamp(since: UtcDateTime?, messageId: Long?): Boolean =
        since == null || messageId != null && UtcDateTime((messageId ushr 22) + DISCORD_EPOCH).compareTo(since) >= 0

    companion object {
        const val UNREAD_MARKER_CACHE_KEY = "UNREAD_MESSAGE_MARKER_V3"
        private const val DISCORD_EPOCH = 1_420_070_400_000L

        internal fun markerFor(channelId: Long, ackAtSwitch: Ack?, liveAck: Ack?, mostRecentMessageId: Long) =
            Unread.Marker(
                channelId,
                (liveAck?.takeIf(Ack::isLockedAck) ?: ackAtSwitch)?.messageId ?: 0L,
                mostRecentMessageId,
            )
    }
}
