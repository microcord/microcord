package com.discord.widgets.guilds.create

import android.os.Parcel
import android.os.Parcelable

data class CreationIntentArgs(
    val trigger: CreateGuildTrigger,
    val createGuildOptions: WidgetGuildCreate.Options,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        CreateGuildTrigger.valueOf(parcel.readString().orEmpty()),
        WidgetGuildCreate.Options.createFromParcel(parcel),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(trigger.name)
        createGuildOptions.writeToParcel(parcel, flags)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<CreationIntentArgs> {
        override fun createFromParcel(parcel: Parcel) = CreationIntentArgs(parcel)
        override fun newArray(size: Int): Array<CreationIntentArgs?> = arrayOfNulls(size)
    }
}
