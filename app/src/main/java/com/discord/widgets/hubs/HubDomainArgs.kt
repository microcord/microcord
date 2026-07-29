package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class HubDomainArgs(val email: String, val guildInfos: List<DomainGuildInfo>) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(email)
        parcel.writeInt(guildInfos.size)
        guildInfos.forEach { it.writeToParcel(parcel, 0) }
    }

    override fun toString() = "HubDomainArgs(guildInfos=$guildInfos, email=<redacted>)"

    companion object CREATOR : Parcelable.Creator<HubDomainArgs> {
        override fun createFromParcel(parcel: Parcel): HubDomainArgs {
            val email = parcel.readString().orEmpty()
            val size = parcel.readInt()
            return HubDomainArgs(email, List(size.coerceAtLeast(0)) { DomainGuildInfo.createFromParcel(parcel) })
        }

        override fun newArray(size: Int): Array<HubDomainArgs?> = arrayOfNulls(size)
    }
}
