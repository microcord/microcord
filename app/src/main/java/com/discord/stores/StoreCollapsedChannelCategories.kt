package com.discord.stores

import android.content.Context
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import rx.Observable

/**
 * Which channel categories the user has collapsed, per guild.
 *
 * A guild with no collapsed categories is removed from the map rather than kept with an empty set,
 * so the persisted form stays proportional to what the user actually collapsed.
 */
class StoreCollapsedChannelCategories(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val cache: Persister<Map<Long, Set<Long>>> = Persister(CACHE_KEY, emptyMap()),
) : StoreV2() {
    private val collapsed = HashMap<Long, MutableSet<Long>>()
    private var snapshot: Map<Long, Set<Long>> = emptyMap()

    @StoreThread
    override fun init(context: Context) {
        super.init(context)
        restore()
    }

    /** Replaces, never merges: a re-hydrate must not resurrect guilds the last one dropped. */
    @StoreThread
    internal fun restore() {
        collapsed.clear()
        cache.get().forEach { (guildId, ids) -> collapsed[guildId] = ids.toMutableSet() }
        markChanged()
    }

    fun getCollapsedCategories(): Map<Long, Set<Long>> = snapshot

    fun observeCollapsedCategories(guildId: Long): Observable<Set<Long>> =
        observationDeck.connectRx(this) { snapshot[guildId].orEmpty() }.distinctUntilChanged()

    fun setCollapsedCategory(guildId: Long, channelId: Long, value: Boolean) = dispatcher.schedule {
        val ids = collapsed.getOrPut(guildId) { HashSet() }
        val changed = if (value) ids.add(channelId) else ids.remove(channelId)
        if (ids.isEmpty()) collapsed.remove(guildId)
        if (changed) markChanged()
    }

    /**
     * Drop collapse state for guilds and categories that no longer exist.
     *
     * Without the prune a deleted category would keep a guild entry alive forever, and the map would
     * only ever grow across sessions.
     */
    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        val channels = payload.guilds.orEmpty()
            .associate { guild -> guild.id to guild.channels.orEmpty().mapTo(HashSet()) { it.id } }
        var changed = collapsed.keys.retainAll(channels.keys)
        collapsed.forEach { (guildId, ids) -> changed = ids.retainAll(channels[guildId].orEmpty()) || changed }
        changed = collapsed.entries.removeAll { it.value.isEmpty() } || changed
        if (changed) markChanged()
    }

    /**
     * Clears the in-memory map only.
     *
     * The persisted map is deliberately left alone: the reference keeps collapse state across a
     * logout and relies on the global `Persister` reset to drop it, so wiping the cache here would
     * lose the user's layout on every sign-out.
     */
    @StoreThread
    internal fun reset() {
        if (collapsed.isEmpty()) return
        collapsed.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        snapshot = collapsed.mapValues { it.value.toSet() }
        cache.set(snapshot)
    }

    companion object { const val CACHE_KEY = "STORE_COLLAPSED_CATEGORIES_V2" }
}
