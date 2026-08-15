package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.applicationcommands.ApplicationCommandFrecencyTracker
import com.discord.utilities.frecency.FrecencyTracker
import com.discord.utilities.persister.Persister
import discord_protos.discord_users.v1.FrecencyUserSettingsOuterClass.FrecencyUserSettings
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
    private val lastUsedCommandCache: Persister<String> =
        Persister(CACHE_KEY_LAST_USED_COMMAND, ""),
) : StoreV2() {

    private val frecency: ApplicationCommandFrecencyTracker = frecencyCache.get()

    private var sortedKeys: List<String> = emptyList()
    private var sortedKeysSnapshot: List<String> = emptyList()
    private var lastUsedCommandIdSnapshot: String? = lastUsedCommandCache.get().takeIf(String::isNotEmpty)

    init {
        populateStore()
    }

    /**
     * Adopts the account's synced ranking.
     *
     * Command frecency rides in the user-settings proto, so the ordering has to come from there
     * rather than from local use alone or this client disagrees with every other one.
     */
    @StoreThread
    fun handleFrecencySettings(settings: FrecencyUserSettings) {
        frecency.restore(
            settings.applicationCommandFrecency.applicationCommandsMap.mapValues { (_, item) ->
                FrecencyTracker.Entry(item.totalUses, item.recentUsesList.toList(), item.frecency, item.score)
            },
        )
        frecencyCache.set(frecency, true)
        populateStore()
        markChanged()
    }

    /** Mirrors the local tracker back into the proto so other clients see what was used here. */
    fun writeInto(builder: FrecencyUserSettings.Builder) {
        builder.applicationCommandFrecencyBuilder.clearApplicationCommands().putAllApplicationCommands(
            frecency.toEntries(MAX_ENTRIES).mapValues { (_, entry) ->
                FrecencyUserSettings.FrecencyItem.newBuilder()
                    .setTotalUses(entry.totalUses)
                    .addAllRecentUses(entry.recentUses)
                    .setFrecency(entry.frecency)
                    .setScore(entry.score)
                    .build()
            },
        )
    }

    /**
     * The ranked command ids usable in [guildId].
     *
     * A key only carries a guild when the command itself is guild-scoped, so an unscoped key is a
     * global command and counts everywhere. Bucketing strictly by guild would hide every global
     * command's ranking the moment the launcher opened inside a guild.
     */
    fun getTopCommandIds(guildId: Long?): List<String> = sortedKeysSnapshot
        .filter { key ->
            val separator = key.indexOf(KEY_SEPARATOR)
            if (separator < 0) true else guildId != null && key.substring(separator + 1) == guildId.toString()
        }
        .map { key -> key.substringBefore(KEY_SEPARATOR) }

    /**
     * The reference pins the command you just ran to the front of the frecency row, ahead of the
     * ranking, so the thing you are most likely to repeat is always first.
     */
    fun getLastUsedCommandId(): String? = lastUsedCommandIdSnapshot

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
        lastUsedCommandCache.set(commandId, true)
        sortedKeys = frecency.getSortedKeys().toList()
        markChanged(this)
    }

    @StoreThread
    fun handlePreLogout() {
        frecencyCache.clear()
        lastUsedCommandCache.clear()
    }

    /** Reads the ranking out of the restored tracker. */
    internal fun populateStore() {
        sortedKeys = frecency.getSortedKeys().toList()
        sortedKeysSnapshot = sortedKeys
    }

    private fun key(guildId: Long?, commandId: String): String =
        if (guildId != null) "$commandId$KEY_SEPARATOR$guildId" else commandId

    @StoreThread
    override fun snapshotData() {
        sortedKeysSnapshot = sortedKeys
        lastUsedCommandIdSnapshot = lastUsedCommandCache.get().takeIf(String::isNotEmpty)
    }

    companion object {
        const val CACHE_KEY_APPLICATION_COMMANDS = "CACHE_KEY_APPLICATION_COMMANDS"
        const val CACHE_KEY_LAST_USED_COMMAND = "CACHE_KEY_LAST_USED_APPLICATION_COMMAND"

        private const val MAX_ENTRIES = 100

        private const val KEY_SEPARATOR = ':'
    }
}
