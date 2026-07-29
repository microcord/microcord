package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class HubDescriptionArgs(
    val guildId: Long,
    val channelId: Long,
    val isEditing: Boolean,
    val hubName: String,
    val isNewGuild: Boolean = false,
    val description: String? = null,
    val primaryCategoryId: Int? = null,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readLong(),
        parcel.readInt() != 0,
        parcel.readString().orEmpty(),
        parcel.readInt() != 0,
        parcel.readString(),
        if (parcel.readInt() != 0) parcel.readInt() else null,
    )

    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(guildId)
        parcel.writeLong(channelId)
        parcel.writeInt(if (isEditing) 1 else 0)
        parcel.writeString(hubName)
        parcel.writeInt(if (isNewGuild) 1 else 0)
        parcel.writeString(description)
        parcel.writeInt(if (primaryCategoryId != null) 1 else 0)
        primaryCategoryId?.let(parcel::writeInt)
    }

    companion object CREATOR : Parcelable.Creator<HubDescriptionArgs> {
        override fun createFromParcel(parcel: Parcel) = HubDescriptionArgs(parcel)
        override fun newArray(size: Int): Array<HubDescriptionArgs?> = arrayOfNulls(size)
    }
}
