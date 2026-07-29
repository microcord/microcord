package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class HubWaitlistArgs(val email: String) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readString().orEmpty())
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) = parcel.writeString(email)
    override fun toString() = "HubWaitlistArgs(email=<redacted>)"

    companion object CREATOR : Parcelable.Creator<HubWaitlistArgs> {
        override fun createFromParcel(parcel: Parcel) = HubWaitlistArgs(parcel)
        override fun newArray(size: Int): Array<HubWaitlistArgs?> = arrayOfNulls(size)
    }
}
