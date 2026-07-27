package com.discord.widgets.guildscheduledevent

import android.os.Parcel
import android.os.Parcelable

/**
 * Where the details sheet was opened from.
 *
 * The two sources resolve the event differently: [Guild] reads it out of
 * `StoreGuildScheduledEvents`, [Directory] reads it out of the hub's directory entries, because a
 * hub can show events for guilds the viewer has not joined and whose events are therefore not in
 * the store.
 */
enum class GuildScheduledEventDetailsSource {
    Directory,
    Guild,
}

/**
 * Arguments of the event details sheet.
 *
 * [guildId] and [channelId] are only meaningful for [GuildScheduledEventDetailsSource.Directory]
 * — they name the hub channel the entry came from and the guild it belongs to. For a guild-sourced
 * event the id alone is enough, because the store can find it across every guild.
 */
data class GuildScheduledEventDetailsArgs(
    val eventId: Long,
    val guildId: Long? = null,
    val channelId: Long? = null,
    val source: GuildScheduledEventDetailsSource = GuildScheduledEventDetailsSource.Guild,
) : Parcelable {

    override fun describeContents(): Int = 0

    // Written by hand rather than with `@Parcelize` because the kotlin-parcelize plugin is not
    // applied in this project. The wire format matches the reference's generated Creator: a
    // present/absent int flag ahead of each nullable long, and the enum by name.
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(eventId)
        parcel.writeNullableLong(guildId)
        parcel.writeNullableLong(channelId)
        parcel.writeString(source.name)
    }

    companion object {
        /** Bundle key the details and extras sheets read their arguments from. */
        const val INTENT_ARGS_KEY: String = "intent_args_key"

        @JvmField
        val CREATOR: Parcelable.Creator<GuildScheduledEventDetailsArgs> =
            object : Parcelable.Creator<GuildScheduledEventDetailsArgs> {
                override fun createFromParcel(parcel: Parcel) = GuildScheduledEventDetailsArgs(
                    eventId = parcel.readLong(),
                    guildId = parcel.readNullableLong(),
                    channelId = parcel.readNullableLong(),
                    source = parcel.readString()
                        ?.let { name -> GuildScheduledEventDetailsSource.entries.firstOrNull { it.name == name } }
                        ?: GuildScheduledEventDetailsSource.Guild,
                )

                override fun newArray(size: Int) = arrayOfNulls<GuildScheduledEventDetailsArgs>(size)
            }

        private fun Parcel.writeNullableLong(value: Long?) {
            if (value == null) {
                writeInt(0)
            } else {
                writeInt(1)
                writeLong(value)
            }
        }

        private fun Parcel.readNullableLong(): Long? = if (readInt() != 0) readLong() else null
    }
}

/** Which half of the details sheet the segmented control currently shows. */
enum class EventDetailsSection {
    EVENT_INFO,
    RSVP_LIST,
}

/**
 * Lifecycle of the RSVP-user fetch behind the details sheet's second tab.
 *
 * Declared in the reference's ordinal order — `LOADING, ERROR, EMPTY, SUCCESS` — which is not the
 * alphabetical order Vineflower prints and is load-bearing wherever the value is persisted.
 */
enum class EventDetailsRsvpUsersFetchState {
    LOADING,
    ERROR,
    EMPTY,
    SUCCESS,
}
