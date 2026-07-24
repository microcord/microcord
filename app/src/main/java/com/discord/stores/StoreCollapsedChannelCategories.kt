package com.discord.stores

import android.content.Context
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import rx.Observable

class StoreCollapsedChannelCategories(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val cache: Persister<Map<Long, Set<Long>>> = Persister(CACHE_KEY, emptyMap()),
) : StoreV2() {
    private val collapsed = HashMap<Long, MutableSet<Long>>()
    private var snapshot: Map<Long, Set<Long>> = emptyMap()

    override fun init(context: Context) = restore()
    internal fun restore() {
        cache.get().forEach { (guildId, ids) -> collapsed[guildId] = ids.toMutableSet() }
        markChanged()
    }

    fun getCollapsedCategories() = snapshot
    fun observeCollapsedCategories(guildId: Long): Observable<Set<Long>> =
        observationDeck.connectRx(this) { snapshot[guildId].orEmpty() }.distinctUntilChanged()
    fun setCollapsedCategory(guildId: Long, channelId: Long, value: Boolean) = dispatcher.schedule {
        val ids = collapsed.getOrPut(guildId) { HashSet() }
        if (value) ids.add(channelId) else ids.remove(channelId)
        if (ids.isEmpty()) collapsed.remove(guildId)
        markChanged()
    }

    fun handleConnectionOpen(payload: ModelPayload) {
        val channels = payload.guilds.orEmpty().associate { guild -> guild.id to guild.channels.orEmpty().mapTo(HashSet()) { it.id } }
        collapsed.keys.retainAll(channels.keys)
        collapsed.forEach { (guildId, ids) -> ids.retainAll(channels[guildId].orEmpty()) }
        collapsed.entries.removeAll { it.value.isEmpty() }
        markChanged()
    }

    internal fun reset() {
        collapsed.clear()
        cache.clear()
        markChanged()
    }

    override fun snapshotData() {
        snapshot = collapsed.mapValues { it.value.toSet() }
        cache.set(snapshot)
    }

    companion object { const val CACHE_KEY = "STORE_COLLAPSED_CATEGORIES_V2" }
}
