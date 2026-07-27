package com.discord.widgets.directories

import com.discord.api.directory.DirectoryEntryGuild
import com.discord.stores.StoreDirectories
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.utilities.RestCallState
import com.discord.widgets.hubs.HubDirectoriesStore
import com.discord.widgets.hubs.HubStores

/**
 * The `StoreDirectories` surface the directory screens read.
 *
 * [com.discord.stores.StoreDirectories] implements this and the sibling hub store contract. They are
 * views of one dispatcher-confined store, not independent REST caches.
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
 * Publishes the directory store. Access before installation fails instead of silently bypassing the
 * dispatcher through a second REST cache.
 */
object DirectoryStores {

    @Volatile
    private var installed: DirectoriesStore? = null

    val directories: DirectoriesStore
        get() = checkNotNull(installed) { "StoreDirectories has not been installed" }

    val isStoreInstalled: Boolean
        get() = installed != null

    @JvmStatic
    fun install(directories: StoreDirectories) {
        installed = directories
        HubStores.install(directories = directories)
    }

    /** Used by tests so one case cannot leak into the next. */
    @JvmStatic
    fun reset() {
        installed = null
        HubStores.install(directories = HubDirectoriesStore.Unavailable)
    }
}
