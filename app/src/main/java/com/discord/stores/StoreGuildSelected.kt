package com.discord.stores

import android.content.Context
import com.discord.api.guild.Guild
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import rx.Observable

class StoreGuildSelected(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
) : StoreV2() {
    private val selectedGuildIds = LinkedHashSet<Long>()
    private var selectedGuildIdsSnapshot: List<Long> = emptyList()
    private val selectedGuildIdsCache = Persister(CACHE_KEY, LinkedHashSet<Long>())

    fun getSelectedGuildId(): Long = selectedGuildIdsSnapshot.firstOrNull() ?: UNSELECTED
    fun getRecentlySelectedGuildIds(): List<Long> = selectedGuildIdsSnapshot
    internal fun getSelectedGuildIdInternal(): Long = selectedGuildIds.lastOrNull() ?: UNSELECTED

    fun observeSelectedGuildId(): Observable<Long> =
        observationDeck.connectRx(this, generator = ::getSelectedGuildId).distinctUntilChanged()

    fun observeRecentSelectedGuildIds(): Observable<List<Long>> =
        observationDeck.connectRx(this, generator = ::getRecentlySelectedGuildIds).distinctUntilChanged()

    fun set(guildId: Long) = dispatcher.schedule { handleGuildSelected(guildId) }

    override fun init(context: Context) = restore()

    internal fun restore() {
        selectedGuildIds.clear()
        selectedGuildIdsCache.get().forEach(::addNewest)
        markChanged()
    }

    fun handleConnectionOpen(payload: ModelPayload) {
        val guildIds = payload.guilds.orEmpty().mapTo(HashSet()) { it.id }
        selectedGuildIds.removeAll { it != UNSELECTED && it !in guildIds }
        markChanged()
    }

    fun handleGuildRemove(guild: Guild) {
        if (guild.unavailable) return
        if (selectedGuildIds.remove(guild.id)) markChanged()
    }

    fun handleGuildSelected(guildId: Long) {
        addNewest(guildId)
        markChanged()
    }

    fun handlePreLogout() = reset()

    internal fun reset() {
        selectedGuildIds.clear()
        selectedGuildIds.add(UNSELECTED)
        selectedGuildIdsCache.clear(persist = true)
        markChanged()
    }

    override fun snapshotData() {
        if (selectedGuildIds.isEmpty()) selectedGuildIds.add(UNSELECTED)
        selectedGuildIdsSnapshot = selectedGuildIds.toList().asReversed()
        selectedGuildIdsCache.set(LinkedHashSet(selectedGuildIds), persist = true)
    }

    private fun addNewest(guildId: Long) {
        selectedGuildIds.remove(guildId)
        selectedGuildIds.add(guildId)
        while (selectedGuildIds.size > MAX_SAVED_GUILD_IDS) {
            selectedGuildIds.remove(selectedGuildIds.first())
        }
    }

    companion object {
        const val UNSELECTED = 0L
        private const val MAX_SAVED_GUILD_IDS = 5
        private const val CACHE_KEY = "STORE_GUILD_SELECTED_V5"
    }
}
