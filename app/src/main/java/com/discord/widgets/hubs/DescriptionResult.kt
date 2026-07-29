package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class DescriptionResult(val guildId: Long, val hubName: String) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readLong(), parcel.readString().orEmpty())
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(guildId)
        parcel.writeString(hubName)
    }

    companion object CREATOR : Parcelable.Creator<DescriptionResult> {
        override fun createFromParcel(parcel: Parcel) = DescriptionResult(parcel)
        override fun newArray(size: Int): Array<DescriptionResult?> = arrayOfNulls(size)
    }
}
