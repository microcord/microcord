package com.discord.stores

import android.content.Context
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import rx.Observable

/**
 * Vocal channels whose participant names the user has hidden in the sidebar.
 *
 * Unlike a collapsed category this is a flat set: a vocal channel is either showing its connected
 * members or showing only its own row, regardless of which guild it belongs to.
 */
class StoreCollapsedVoiceChannels(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val cache: Persister<Set<Long>> = Persister(CACHE_KEY, emptySet()),
) : StoreV2() {
    private val collapsed = HashSet<Long>()
    private var snapshot: Set<Long> = emptySet()

    @StoreThread
    override fun init(context: Context) {
        super.init(context)
        restore()
    }

    @StoreThread
    internal fun restore() {
        collapsed.clear()
        collapsed.addAll(cache.get())
        markChanged()
    }

    fun isCollapsed(channelId: Long): Boolean = channelId in snapshot

    fun observeCollapsedVoiceChannels(): Observable<Set<Long>> =
        observationDeck.connectRx(this) { snapshot }.distinctUntilChanged()

    fun toggle(channelId: Long) = dispatcher.schedule {
        if (!collapsed.remove(channelId)) collapsed.add(channelId)
        markChanged()
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        val known = payload.guilds.orEmpty().flatMapTo(HashSet()) { guild ->
            guild.channels.orEmpty().map { it.id }
        }
        if (collapsed.retainAll(known)) markChanged()
    }

    @StoreThread
    internal fun reset() {
        if (collapsed.isEmpty()) return
        collapsed.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        snapshot = collapsed.toSet()
        cache.set(snapshot)
    }

    companion object { const val CACHE_KEY = "STORE_COLLAPSED_VOICE_CHANNELS" }
}
