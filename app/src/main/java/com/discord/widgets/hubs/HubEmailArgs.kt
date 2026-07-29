package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class HubEmailArgs(
    val guildName: String = "",
    val guildMemberCount: Int = 0,
    val entryPoint: HubEmailEntryPoint = HubEmailEntryPoint.Default,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString().orEmpty(),
        parcel.readInt(),
        parcel.readString()?.let { name -> HubEmailEntryPoint.values().firstOrNull { it.name == name } }
            ?: HubEmailEntryPoint.Default,
    )

    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(guildName)
        parcel.writeInt(guildMemberCount)
        parcel.writeString(entryPoint.name)
    }

    companion object CREATOR : Parcelable.Creator<HubEmailArgs> {
        override fun createFromParcel(parcel: Parcel) = HubEmailArgs(parcel)
        override fun newArray(size: Int): Array<HubEmailArgs?> = arrayOfNulls(size)
    }
}
