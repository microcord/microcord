package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class AuthenticationResult(val guildId: Long) : MultipleDomainResult {
    constructor(parcel: Parcel) : this(parcel.readLong())
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) = parcel.writeLong(guildId)

    companion object CREATOR : Parcelable.Creator<AuthenticationResult> {
        override fun createFromParcel(parcel: Parcel) = AuthenticationResult(parcel)
        override fun newArray(size: Int): Array<AuthenticationResult?> = arrayOfNulls(size)
    }
}
