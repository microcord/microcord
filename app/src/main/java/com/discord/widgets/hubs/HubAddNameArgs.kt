package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class HubAddNameArgs(val guildId: Long) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readLong())
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) = parcel.writeLong(guildId)

    companion object CREATOR : Parcelable.Creator<HubAddNameArgs> {
        override fun createFromParcel(parcel: Parcel) = HubAddNameArgs(parcel)
        override fun newArray(size: Int): Array<HubAddNameArgs?> = arrayOfNulls(size)
    }
}
