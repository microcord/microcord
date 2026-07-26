package com.discord.widgets.directories

import com.discord.api.directory.DirectoryEntryGuild
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.stores.utilities.RestCallState
import com.discord.stores.utilities.Success
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.rest.RestAPI

/**
 * The `StoreDirectories` surface the directory screens read.
 *
 * `com.discord.stores.StoreDirectories` has not been reconstructed. The sibling hub screens declared
 * the slice *they* need as `com.discord.widgets.hubs.HubDirectoriesStore`; this is the slice the
 * directory channel and directory search need. **They are two halves of one store** — see the
 * handoff report for the union, which is exactly the reference's `StoreDirectories` public surface.
 *
 * Implementations are [ObservationDeck.UpdateSource]s so a view model can put the store in the same
 * `connectRx` set as `StoreGuilds`/`StorePermissions` and recompute its whole view state at once,
 * which is what the reference does.
 */
interface DirectoriesStore : ObservationDeck.UpdateSource {

    /** The entry fetch for [channelId], or `null` when none was ever started. */
    fun getDirectoriesForChannel(channelId: Long): RestCallState<List<DirectoryEntryGuild>>?

    /** Starts (or restarts) the entry fetch for [channelId]. */
    fun fetchDirectoriesForChannel(channelId: Long)

    /** The per-category entry counts for [channelId], or `null` when none was ever started. */
    fun getEntryCountsForChannel(channelId: Long): RestCallState<Map<Int, Int>>?

    /** Starts (or restarts) the entry-count fetch for [channelId]. */
    fun fetchEntryCountsForChannel(channelId: Long)

    /** Records a server this client just added, so the page updates without a refetch. */
    fun addServerToDirectory(channelId: Long, directoryEntry: DirectoryEntryGuild)

    /** Removes [guildId] from [channelId], both remotely and in the cached page. */
    fun removeServerFromDirectory(channelId: Long, guildId: Long)
}

/**
 * Publishes the directory store.
 *
 * The store graph calls [install] once `StoreDirectories` exists; until then the screens run on
 * [RestDirectoriesSource], which is deliberately *not* a store — no dispatcher, no persistence, no
 * snapshotting — just enough caching to hold a `RestCallState` per channel so the surface has real
 * loading/success/failure states instead of silently rendering nothing.
 *
 * `com.discord.widgets.hubs.HubStores` is the equivalent hook for the hub screens; both collapse
 * into `StoreStream.getDirectories()` when the store lands.
 */
object DirectoryStores {

    @Volatile
    var directories: DirectoriesStore = RestDirectoriesSource
        private set

    /** `true` while the screens are still running on the REST-only stand-in. */
    val isStoreInstalled: Boolean
        get() = directories !== RestDirectoriesSource

    @JvmStatic
    fun install(directories: DirectoriesStore) {
        this.directories = directories
    }

    /** Restores the stand-in. Used by tests so one case cannot leak into the next. */
    @JvmStatic
    fun reset() {
        RestDirectoriesSource.clear()
        directories = RestDirectoriesSource
    }
}

/**
 * Temporary seam: talks to the directory REST endpoints directly and remembers the last
 * [RestCallState] per channel.
 *
 * This exists so the directory surface works end to end today. It is **not** a reconstruction of
 * `StoreDirectories` and must be deleted when that store lands: it has no `Dispatcher` confinement,
 * no `snapshotData`, no `Persister`, and no gateway-driven invalidation. The counterpart in the chat
 * wave is `InviteEmbedStore`, recorded in `docs/parity-audit/followups.md` under the same rule.
 */
internal object RestDirectoriesSource : DirectoriesStore {

    private val observationDeck: ObservationDeck get() = ObservationDeckProvider.get()

    private val entriesByChannel = LinkedHashMap<Long, RestCallState<List<DirectoryEntryGuild>>>()
    private val countsByChannel = LinkedHashMap<Long, RestCallState<Map<Int, Int>>>()

    @Synchronized
    override fun getDirectoriesForChannel(channelId: Long): RestCallState<List<DirectoryEntryGuild>>? =
        entriesByChannel[channelId]

    override fun fetchDirectoriesForChannel(channelId: Long) {
        val api = RestAPI.apiOrNull ?: return
        // The reference sorts server-side order by member count descending before caching, so the
        // "All Servers" section reads largest-first without the page re-sorting on every bind.
        api.getDirectoryEntries(channelId)
            .map { entries -> entries.sortedByDescending { it.guild.approximateMemberCount ?: 0 } }
            .executeRequest { state -> put(entriesByChannel, channelId, state) }
    }

    @Synchronized
    override fun getEntryCountsForChannel(channelId: Long): RestCallState<Map<Int, Int>>? =
        countsByChannel[channelId]

    override fun fetchEntryCountsForChannel(channelId: Long) {
        val api = RestAPI.apiOrNull ?: return
        api.getEntryCounts(channelId).executeRequest { state -> put(countsByChannel, channelId, state) }
    }

    override fun addServerToDirectory(channelId: Long, directoryEntry: DirectoryEntryGuild) {
        val current = getDirectoriesForChannel(channelId)?.invoke().orEmpty()
        var replaced = false
        val merged = current.map { existing ->
            if (existing.guild.id == directoryEntry.guild.id) {
                replaced = true
                directoryEntry
            } else {
                existing
            }
        }
        put(entriesByChannel, channelId, Success(if (replaced) merged else merged + directoryEntry))
    }

    override fun removeServerFromDirectory(channelId: Long, guildId: Long) {
        val api = RestAPI.apiOrNull ?: return
        api.removeServerFromHub(channelId, guildId).executeRequest { state ->
            if (state !is Success) return@executeRequest
            val remaining = getDirectoriesForChannel(channelId)?.invoke()
                .orEmpty()
                .filterNot { it.guild.id == guildId }
            put(entriesByChannel, channelId, Success(remaining))
        }
    }

    @Synchronized
    fun clear() {
        entriesByChannel.clear()
        countsByChannel.clear()
    }

    private fun <T> put(target: MutableMap<Long, RestCallState<T>>, channelId: Long, state: RestCallState<T>) {
        synchronized(this) { target[channelId] = state }
        observationDeck.notify(setOf(this))
    }
}
