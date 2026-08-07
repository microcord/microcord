package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.channel.isSpoilerChannel
import com.discord.stores.updates.ObservationDeck
import rx.Observable

/**
 * Which spoiler channels the user has chosen to reveal.
 *
 * Agreement is per device and per account, and survives a logout, so it lives in [prefs] keyed by
 * user id rather than in a [com.discord.utilities.persister.Persister].
 */
class StoreChannelSpoilerAgree(
    private val dispatcher: Dispatcher,
    private val storeChannels: StoreChannels,
    private val storeUsers: StoreUser,
    private val observationDeck: ObservationDeck,
) : StoreV2() {
    private val agreedChannelIds = LinkedHashSet<Long>()
    private var agreedChannelIdsSnapshot: Set<Long> = emptySet()
    private var loadedForUserId = 0L

    @StoreThread
    override fun init(context: Context) {
        super.init(context)
        load(storeUsers.getMeInternal().id)
    }

    @StoreThread
    private fun load(userId: Long) {
        loadedForUserId = userId
        agreedChannelIds.clear()
        if (userId != 0L) {
            agreedChannelIds.addAll(
                prefs.getStringSet(key(userId), emptySet()).orEmpty().mapNotNull(String::toLongOrNull),
            )
        }
        markChanged()
    }

    fun didAgree(channelId: Long?): Boolean = channelId != null && channelId in agreedChannelIdsSnapshot

    /**
     * The channel whose gate stands between the viewer and [channel], or null when nothing is
     * hidden. A thread or forum post is gated by its parent, which is where the flag is set.
     */
    fun getSpoilerGatingChannelId(channel: Channel?): Long? {
        channel ?: return null
        if (channel.isSpoilerChannel()) return channel.id.takeUnless { didAgree(it) }
        val parent = storeChannels.getChannel(channel.parentId) ?: return null
        return parent.id.takeIf { parent.isSpoilerChannel() && !didAgree(it) }
    }

    fun isChannelSpoilerGated(channel: Channel?): Boolean = getSpoilerGatingChannelId(channel) != null

    fun observeIsChannelSpoilerGated(channelId: Long): Observable<Boolean> =
        observationDeck.connectRx(this) { isChannelSpoilerGated(storeChannels.getChannel(channelId)) }

    /** Emits whenever any agreement is granted or cleared, for surfaces that re-read on their own. */
    fun observeAnyAgreementChange(): Observable<Set<Long>> =
        observationDeck.connectRx(this) { agreedChannelIdsSnapshot }

    fun agree(channelId: Long) = dispatcher.schedule {
        agreedChannelIds.add(channelId)
        markChanged()
    }

    fun clearAgreement(channelId: Long) = dispatcher.schedule {
        agreedChannelIds.remove(channelId)
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        val userId = storeUsers.getMeInternal().id
        if (userId != loadedForUserId) load(userId)
        agreedChannelIdsSnapshot = HashSet(agreedChannelIds)
        if (userId != 0L) {
            prefs.edit().putStringSet(key(userId), agreedChannelIds.map(Long::toString).toSet()).apply()
        }
    }

    private fun key(userId: Long) = "$CHANNELS_AGREED_KEY$userId"

    private companion object {
        private const val CHANNELS_AGREED_KEY = "SPOILER_CHANNELS_AGREED_"
    }
}
