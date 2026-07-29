package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.hubs.GuildInfo

data class DomainGuildInfo(val icon: String?, val id: Long, val name: String) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readString(), parcel.readLong(), parcel.readString().orEmpty())
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(icon)
        parcel.writeLong(id)
        parcel.writeString(name)
    }

    companion object CREATOR : Parcelable.Creator<DomainGuildInfo> {
        override fun createFromParcel(parcel: Parcel) = DomainGuildInfo(parcel)
        override fun newArray(size: Int): Array<DomainGuildInfo?> = arrayOfNulls(size)
        @JvmStatic fun from(guildInfo: GuildInfo) = DomainGuildInfo(guildInfo.icon, guildInfo.id, guildInfo.name)
    }
}
