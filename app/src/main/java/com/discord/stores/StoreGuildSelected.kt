package com.discord.stores

import android.content.Context
import com.discord.api.guild.Guild as ApiGuild
import com.discord.models.domain.ModelPayload
import com.discord.models.guild.Guild
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.collections.LeastRecentlyAddedSet
import com.discord.utilities.persister.Persister
import rx.Observable

/**
 * Which guild is selected, and the few most recently selected before it.
 *
 * The set is bounded at [MAX_SAVED_GUILD_IDS] and ordered oldest-first, so the *last* element is
 * the current selection. [UNSELECTED] (`0`) is a real member of the set and means the home/DM
 * surface — it is re-inserted whenever the set would otherwise be empty, which is why
 * [getSelectedGuildId] can always answer.
 *
 * The recency list is persisted so a cold start reopens the guild the user left off in, minus any
 * guild they were only lurking: a lurked guild is gone by the next launch and would otherwise
 * restore into a server the user is not in.
 */
class StoreGuildSelected(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val guildStore: StoreGuilds? = null,
    /** Wire to `StoreLurking::getLurkingGuildIdsSync`. */
    private val getLurkingGuildIds: () -> Set<Long> = { emptySet() },
    /** Wire to `StoreAnalytics::trackGuildViewed`. */
    private val trackGuildViewed: (Long) -> Unit = {},
    /** Wire to `StoreStream::handleSamplePremiumGuildSelected`. */
    private val onSampleGuildIdSelected: (Long) -> Unit = {},
) : StoreV2() {
    private val selectedGuildIds = LeastRecentlyAddedSet<Long>(MAX_SAVED_GUILD_IDS)
    private var selectedGuildIdsSnapshot: List<Long> = emptyList()
    private val selectedGuildIdsCache = Persister(CACHE_KEY, LeastRecentlyAddedSet<Long>(MAX_SAVED_GUILD_IDS))

    fun getSelectedGuildId(): Long = selectedGuildIdsSnapshot.firstOrNull() ?: UNSELECTED
    fun getRecentlySelectedGuildIds(): List<Long> = selectedGuildIdsSnapshot
    internal fun getSelectedGuildIdInternal(): Long = selectedGuildIds.lastOrNull() ?: UNSELECTED

    fun observeSelectedGuildId(): Observable<Long> =
        observationDeck.connectRx(this, generator = ::getSelectedGuildId).distinctUntilChanged()

    fun observeRecentSelectedGuildIds(): Observable<List<Long>> =
        observationDeck.connectRx(this, generator = ::getRecentlySelectedGuildIds).distinctUntilChanged()

    /**
     * The selected guild resolved against [StoreGuilds].
     *
     * Depends on both stores, because the selection can stay put while the guild itself changes
     * (rename, icon, boost level) and the header has to follow.
     */
    fun observeSelectedGuild(): Observable<Guild?> {
        val guildStore = guildStore ?: return Observable.just(null)
        return observationDeck
            .connectRx(this, guildStore) { guildStore.getGuilds()[getSelectedGuildId()] }
            .distinctUntilChanged()
    }

    fun set(guildId: Long) = dispatcher.schedule { handleGuildSelected(guildId) }

    /** Reports a guild opened from a premium/boost sample surface to the analytics pipeline. */
    fun dispatchSampleGuildIdSelected(guildId: Long) = dispatcher.schedule {
        onSampleGuildIdSelected(guildId)
    }

    override fun init(context: Context) = restore()

    internal fun restore() {
        selectedGuildIds.clear()
        selectedGuildIds.addAll(readPersistedIds())
        markChanged()
    }

    /**
     * Drops any remembered guild the account is no longer in.
     *
     * [UNSELECTED] is spared explicitly — it is never present in `payload.guilds` and losing it
     * would make the home surface un-restorable.
     */
    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        val guildIds = payload.guilds.orEmpty().mapTo(HashSet()) { it.id }
        selectedGuildIds.removeAll { it != UNSELECTED && it !in guildIds }
        markChanged()
    }

    @StoreThread
    fun handleGuildRemove(guild: ApiGuild) {
        if (guild.unavailable) return
        if (selectedGuildIds.remove(guild.id)) markChanged()
    }

    @StoreThread
    fun handleGuildSelected(guildId: Long) {
        selectedGuildIds.add(guildId)
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        selectedGuildIds.clear()
        markChanged()
    }

    @StoreThread
    internal fun reset() {
        selectedGuildIds.clear()
        selectedGuildIds.add(UNSELECTED)
        selectedGuildIdsCache.clear(persist = true)
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        if (selectedGuildIds.isEmpty()) selectedGuildIds.add(UNSELECTED)
        trackGuildViewed(selectedGuildIds.last())
        val lurkingGuildIds = getLurkingGuildIds()
        val persisted = LeastRecentlyAddedSet<Long>(MAX_SAVED_GUILD_IDS)
        persisted.addAll(selectedGuildIds.filterNot(lurkingGuildIds::contains))
        selectedGuildIdsCache.set(persisted, persist = true)
        selectedGuildIdsSnapshot = selectedGuildIds.toList().asReversed()
    }

    /**
     * Reads the persisted recency list.
     *
     * An earlier build of this project wrote a plain `LinkedHashSet` under the same key. Kryo
     * restores whatever class it recorded, so the value can legitimately be either type; both are
     * `Set<Long>`, and anything else is discarded rather than crashing the launch.
     */
    private fun readPersistedIds(): Collection<Long> =
        @Suppress("SwallowedException")
        try {
            selectedGuildIdsCache.get()
        } catch (migrationFailure: RuntimeException) {
            selectedGuildIdsCache.clear(persist = true)
            emptySet()
        }

    companion object {
        const val UNSELECTED = 0L
        private const val MAX_SAVED_GUILD_IDS = 5
        private const val CACHE_KEY = "STORE_GUILD_SELECTED_V5"
    }
}
