package com.discord.widgets.hubs

import com.discord.api.directory.DirectoryEntryEvent
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

/**
 * The store surface the Student Hub screens need, declared here because the stores do not exist yet.
 *
 * The reference reads all of this from `StoreDirectories`, `StoreGuildScheduledEvents` and
 * `StoreVoiceChannelSelected` via `StoreStream`. In this project `StoreDirectories` and
 * `StoreGuildScheduledEvents` have not been reconstructed and `StoreVoiceChannelSelected` exists but
 * is not published by `StoreStream`, so the hub view models depend on these interfaces instead of on
 * concrete stores.
 *
 * When the store wave lands, the real stores implement these interfaces (or the view models switch
 * to them directly and this file is deleted); nothing else in the package changes. Until then
 * [HubStores] serves the [Unavailable] implementations, which report "no data" rather than pretending
 * a request succeeded — every hub screen renders its empty/error state instead of a blank surface.
 */
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

/** The slice of `StoreGuildScheduledEvents` the hub events page needs. */
interface HubGuildScheduledEventsStore : ObservationDeck.UpdateSource {

    /** Ids of the events in [guildId] the current user marked interest in. */
    fun getMeGuildScheduledEventIds(guildId: Long): Set<Long>

    /** Flips the current user's interest in [event]. */
    fun toggleMeRsvpForEvent(event: GuildScheduledEvent)

    object Unavailable : HubGuildScheduledEventsStore {
        override fun getMeGuildScheduledEventIds(guildId: Long): Set<Long> = emptySet()
        override fun toggleMeRsvpForEvent(event: GuildScheduledEvent) = Unit
    }
}

/** The slice of `StoreVoiceChannelSelected` the hub events page needs. */
interface HubVoiceChannelSelection : ObservationDeck.UpdateSource {

    /** The channel the user is connected to, or 0 when not connected. */
    fun getSelectedVoiceChannelId(): Long

    object Unavailable : HubVoiceChannelSelection {
        override fun getSelectedVoiceChannelId(): Long = 0L
    }
}

/**
 * Publishes the hub stores, in the same shape as `com.discord.utilities.rest.RestAPI`.
 *
 * The store graph calls [install] once it owns real implementations. Reads go through the
 * properties, so nothing in this package holds a mutable global callback.
 */
object HubStores {

    @Volatile
    var directories: HubDirectoriesStore = HubDirectoriesStore.Unavailable
        private set

    @Volatile
    var guildScheduledEvents: HubGuildScheduledEventsStore = HubGuildScheduledEventsStore.Unavailable
        private set

    @Volatile
    var voiceChannelSelection: HubVoiceChannelSelection = HubVoiceChannelSelection.Unavailable
        private set

    /** `true` while any hub store is still the inert stand-in; screens surface an error state. */
    val isFullyInstalled: Boolean
        get() = directories !== HubDirectoriesStore.Unavailable &&
            guildScheduledEvents !== HubGuildScheduledEventsStore.Unavailable &&
            voiceChannelSelection !== HubVoiceChannelSelection.Unavailable

    @JvmStatic
    fun install(
        directories: HubDirectoriesStore = this.directories,
        guildScheduledEvents: HubGuildScheduledEventsStore = this.guildScheduledEvents,
        voiceChannelSelection: HubVoiceChannelSelection = this.voiceChannelSelection,
    ) {
        this.directories = directories
        this.guildScheduledEvents = guildScheduledEvents
        this.voiceChannelSelection = voiceChannelSelection
    }

    /** Restores the stand-ins. Used by tests so one case cannot leak into the next. */
    @JvmStatic
    fun reset() {
        directories = HubDirectoriesStore.Unavailable
        guildScheduledEvents = HubGuildScheduledEventsStore.Unavailable
        voiceChannelSelection = HubVoiceChannelSelection.Unavailable
    }
}

/**
 * The state a hub screen shows when the request behind it never started because the owning store is
 * missing. Distinct from [Default] so a screen can tell "not asked yet" from "cannot ask".
 */
internal fun <T> RestCallState<T>?.orUnavailable(): RestCallState<T> = this ?: Default
