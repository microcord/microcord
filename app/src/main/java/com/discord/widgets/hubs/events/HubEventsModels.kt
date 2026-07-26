package com.discord.widgets.hubs.events

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.channel.Channel
import com.discord.api.directory.DirectoryEntryEvent
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/** Arguments for [WidgetHubEventsPage]: which hub, and which of its directory channels. */
data class HubEventsArgs(
    val guildId: Long,
    val directoryChannelId: Long,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        guildId = parcel.readLong(),
        directoryChannelId = parcel.readLong(),
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(guildId)
        parcel.writeLong(directoryChannelId)
    }

    companion object CREATOR : Parcelable.Creator<HubEventsArgs> {
        override fun createFromParcel(parcel: Parcel) = HubEventsArgs(parcel)
        override fun newArray(size: Int): Array<HubEventsArgs?> = arrayOfNulls(size)
    }
}

/**
 * One scheduled event as the hub events page needs it: the event itself plus everything the row has
 * to know that the event payload does not carry.
 *
 * [canStartEvent], [canConnect] and [isConnected] are unused by the directory row's button
 * configuration but are part of the reference model and are populated; see [WidgetHubEventsViewModel]
 * for how each is derived.
 */
data class HubGuildScheduledEventData(
    val directoryChannelId: Long,
    val event: GuildScheduledEvent,
    val channel: Channel?,
    val isRsvped: Boolean,
    val canShare: Boolean,
    val canStartEvent: Boolean,
    val canConnect: Boolean,
    val isConnected: Boolean,
    val isInGuild: Boolean,
)

/**
 * A row of [WidgetHubEventsPage].
 *
 * The list is always header (unless dismissed) + events + footer, so the footer doubles as the empty
 * state ("no events yet, check back") and as the retry control — it carries the fetch's
 * [RestCallState] and renders its spinner.
 */
sealed class HubEventsPage(override val type: Int) : MGRecyclerDataPayload {

    object Header : HubEventsPage(HEADER) {
        override val key: String = "header"
    }

    data class Event(val guildScheduledEventData: HubGuildScheduledEventData) : HubEventsPage(ENTRY) {
        override val key: String = guildScheduledEventData.event.id.toString()
    }

    data class Footer(val eventsAsync: RestCallState<List<DirectoryEntryEvent>>) : HubEventsPage(FOOTER) {
        override val key: String = "footer"
    }

    companion object {
        internal const val HEADER: Int = 0
        internal const val FOOTER: Int = 1
        internal const val ENTRY: Int = 2
    }
}

/** View state of [WidgetHubEventsPage]. */
data class WidgetHubEventsState(
    val showHeader: Boolean = true,
    val eventsData: List<HubGuildScheduledEventData> = emptyList(),
    val eventsAsync: RestCallState<List<DirectoryEntryEvent>> = Default,
)

/** What a row reports back to [WidgetHubEventsPage]. */
interface HubEventsEventListener {
    fun dismissHeader()
    fun fetchGuildScheduledEvents()
    fun onCardClicked(eventData: HubGuildScheduledEventData)
    fun onPrimaryButtonClicked(eventData: HubGuildScheduledEventData)
    fun onSecondaryButtonClicked(eventData: HubGuildScheduledEventData)
    fun onShareClicked(eventData: HubGuildScheduledEventData)
}
