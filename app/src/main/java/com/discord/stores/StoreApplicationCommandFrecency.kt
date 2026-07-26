package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.applicationcommands.ApplicationCommandFrecencyTracker
import com.discord.utilities.persister.Persister
import rx.Observable

/**
 * Which slash commands this user reaches for most, per guild.
 *
 * The tracker itself is flat, so the guild is folded into the key as `"<commandId>:<guildId>"`;
 * a command used outside a guild (a DM, or a built-in) is tracked under the bare `"<commandId>"`.
 * [topCommandIds] is the decoded view of that, keyed by guild id with `0L` standing for
 * "no guild" — the same slot [getTopCommandIds] falls back to when asked with `null`.
 *
 * The whole thing is persisted, so the picker is already ordered on the first frame after a cold
 * start; [populateStore] runs from the constructor to decode the restored tracker before anything
 * observes it.
 */
class StoreApplicationCommandFrecency(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val frecencyCache: Persister<ApplicationCommandFrecencyTracker> =
        Persister(CACHE_KEY_APPLICATION_COMMANDS, ApplicationCommandFrecencyTracker()),
) : StoreV2() {

    private val frecency: ApplicationCommandFrecencyTracker = frecencyCache.get()

    private var topCommandIds: MutableMap<Long, List<String>> = LinkedHashMap()
    private var topCommandIdsSnapshot: Map<Long, List<String>> = emptyMap()

    init {
        populateStore()
    }

    fun getTopCommandIds(guildId: Long?): List<String> =
        topCommandIdsSnapshot[guildId ?: NO_GUILD].orEmpty()

    /**
     * Starts with the current value rather than waiting for a dispatch, so the picker never renders
     * an empty "Frequently Used" row on first bind.
     */
    fun observeTopCommandIds(guildId: Long?): Observable<List<String>> =
        observationDeck.connectRx(this) { getTopCommandIds(guildId) }
            .startWith(getTopCommandIds(guildId))

    fun onCommandUsed(guildId: Long?, commandId: String) = dispatcher.schedule {
        frecency.track(key(guildId, commandId))
        frecencyCache.set(frecency, true)
        topCommandIds[guildId ?: NO_GUILD] = allTopCommandIds(guildId)
        markChanged(this)
    }

    @StoreThread
    fun handlePreLogout() {
        frecencyCache.clear()
    }

    /** Decodes the persisted tracker into the per-guild view. */
    internal fun populateStore() {
        val decoded = LinkedHashMap<Long, MutableList<String>>()
        frecency.getSortedKeys().forEach { key ->
            val separator = key.indexOf(KEY_SEPARATOR)
            val guildId: Long
            val commandId: String
            if (separator >= 0) {
                guildId = key.substring(separator + 1).toLongOrNull() ?: NO_GUILD
                commandId = key.substring(0, separator)
            } else {
                guildId = NO_GUILD
                commandId = key
            }
            decoded.getOrPut(guildId) { ArrayList() }.add(commandId)
        }
        topCommandIds = decoded.mapValues { (_, ids) -> ids.toList() }.toMutableMap()
        topCommandIdsSnapshot = decoded
    }

    /**
     * The ranked command ids for one guild.
     *
     * A `null` [guildId] deliberately selects only the *unscoped* keys — it is not "any guild".
     */
    private fun allTopCommandIds(guildId: Long?): List<String> =
        frecency.getSortedKeys()
            .filter { key ->
                val separator = key.indexOf(KEY_SEPARATOR)
                if (guildId != null) {
                    separator >= 0 && key.substring(separator + 1) == guildId.toString()
                } else {
                    separator < 0
                }
            }
            .map { key -> key.substringBefore(KEY_SEPARATOR) }

    private fun key(guildId: Long?, commandId: String): String =
        if (guildId != null) "$commandId$KEY_SEPARATOR$guildId" else commandId

    @StoreThread
    override fun snapshotData() {
        topCommandIdsSnapshot = HashMap(topCommandIds)
    }

    companion object {
        const val CACHE_KEY_APPLICATION_COMMANDS = "CACHE_KEY_APPLICATION_COMMANDS"

        /** Slot for commands used outside any guild. */
        const val NO_GUILD = 0L

        private const val KEY_SEPARATOR = ':'
    }
}
