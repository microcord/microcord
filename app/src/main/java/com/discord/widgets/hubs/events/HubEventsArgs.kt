package com.discord.widgets.hubs.events

import android.os.Parcel
import android.os.Parcelable

data class HubEventsArgs(val guildId: Long, val directoryChannelId: Long) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readLong(), parcel.readLong())
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(guildId)
        parcel.writeLong(directoryChannelId)
    }

    companion object CREATOR : Parcelable.Creator<HubEventsArgs> {
        override fun createFromParcel(parcel: Parcel) = HubEventsArgs(parcel)
        override fun newArray(size: Int): Array<HubEventsArgs?> = arrayOfNulls(size)
    }
}
