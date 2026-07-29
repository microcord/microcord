package com.discord.widgets.hubs

import com.discord.api.directory.DirectoryEntryEvent
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.stores.StoreDirectories
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.utilities.RestCallState
import com.discord.widgets.directories.DirectoryStores

/** Compatibility surface used by the directory registry. Hub screens use [StoreDirectories] directly. */
interface HubDirectoriesStore : ObservationDeck.UpdateSource {

    /**
     * The server-listing fetch for [directoryChannelId], or `null` when none was ever started.
     * Reference name and shape: `StoreDirectories.getDirectoriesForChannel(channelId)`.
     */
    fun getDirectoriesForChannel(directoryChannelId: Long): RestCallState<List<DirectoryEntryGuild>>?

    /** Records a server this client just added, so the "Added" tab updates without a refetch. */
    fun addServerToDirectory(directoryChannelId: Long, entry: DirectoryEntryGuild)

    /** Drops a server this client just removed from [directoryChannelId]. */
    fun removeServerFromDirectory(directoryChannelId: Long, guildId: Long)

    /** The scheduled-event fetch for [directoryChannelId], or `null` when none was ever started. */
    fun getGuildScheduledEventsForChannel(directoryChannelId: Long): RestCallState<List<DirectoryEntryEvent>>?

    /** Starts (or restarts) the scheduled-event fetch for [directoryChannelId]. */
    fun fetchGuildScheduledEventsForChannel(guildId: Long, directoryChannelId: Long)

    /** Whether the user already dismissed the "what are hub events" header. Persisted. */
    fun getGuildScheduledEventsHeaderDismissed(): Boolean

    /** Persists the header dismissal. */
    fun markGuildScheduledEventsHeaderDismissed()

    /** Inert stand-in used until the real store exists. */
    object Unavailable : HubDirectoriesStore {
        override fun getDirectoriesForChannel(directoryChannelId: Long): RestCallState<List<DirectoryEntryGuild>>? = null
        override fun addServerToDirectory(directoryChannelId: Long, entry: DirectoryEntryGuild) = Unit
        override fun removeServerFromDirectory(directoryChannelId: Long, guildId: Long) = Unit
        override fun getGuildScheduledEventsForChannel(directoryChannelId: Long): RestCallState<List<DirectoryEntryEvent>>? = null
        override fun fetchGuildScheduledEventsForChannel(guildId: Long, directoryChannelId: Long) = Unit
        override fun getGuildScheduledEventsHeaderDismissed(): Boolean = false
        override fun markGuildScheduledEventsHeaderDismissed() = Unit
    }
}

/** Compatibility bridge for directory code that is installed before the StoreStream accessor lands. */
object HubStores {

    @Volatile
    var directories: HubDirectoriesStore = HubDirectoriesStore.Unavailable
        private set

    @JvmStatic
    fun install(directories: HubDirectoriesStore) {
        this.directories = directories
    }

    @JvmStatic
    fun reset() {
        directories = HubDirectoriesStore.Unavailable
    }
}

internal fun storeDirectories(): StoreDirectories = DirectoryStores.directories as StoreDirectories
