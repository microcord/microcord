package com.discord.widgets.nux

import android.os.Parcel
import android.os.Parcelable

data class GuildCreateArgs(
    val isNux: Boolean,
    val location: String,
    val trigger: CreateGuildTrigger,
    val guildTemplate: GuildTemplateArgs,
    val closeWithResult: Boolean = false,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        isNux = parcel.readInt() != 0,
        location = parcel.readString().orEmpty(),
        trigger = CreateGuildTrigger.valueOf(parcel.readString().orEmpty()),
        guildTemplate = GuildTemplateArgs.createFromParcel(parcel),
        closeWithResult = parcel.readInt() != 0,
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(if (isNux) 1 else 0)
        parcel.writeString(location)
        parcel.writeString(trigger.name)
        guildTemplate.writeToParcel(parcel, flags)
        parcel.writeInt(if (closeWithResult) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<GuildCreateArgs> {
        override fun createFromParcel(parcel: Parcel) = GuildCreateArgs(parcel)

        override fun newArray(size: Int): Array<GuildCreateArgs?> = arrayOfNulls(size)
    }
}
