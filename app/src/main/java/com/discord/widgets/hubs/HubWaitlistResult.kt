package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class HubWaitlistResult(val school: String) : MultipleDomainResult {
    constructor(parcel: Parcel) : this(parcel.readString().orEmpty())
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) = parcel.writeString(school)

    companion object CREATOR : Parcelable.Creator<HubWaitlistResult> {
        override fun createFromParcel(parcel: Parcel) = HubWaitlistResult(parcel)
        override fun newArray(size: Int): Array<HubWaitlistResult?> = arrayOfNulls(size)
    }
}
