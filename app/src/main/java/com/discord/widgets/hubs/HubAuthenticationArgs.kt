package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class HubAuthenticationArgs(
    val email: String = "",
    val guildId: Long? = null,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString().orEmpty(),
        if (parcel.readInt() != 0) parcel.readLong() else null,
    )

    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(email)
        parcel.writeInt(if (guildId != null) 1 else 0)
        guildId?.let(parcel::writeLong)
    }

    override fun toString() = "HubAuthenticationArgs(guildId=$guildId, email=<redacted>)"

    companion object CREATOR : Parcelable.Creator<HubAuthenticationArgs> {
        override fun createFromParcel(parcel: Parcel) = HubAuthenticationArgs(parcel)
        override fun newArray(size: Int): Array<HubAuthenticationArgs?> = arrayOfNulls(size)
    }
}
