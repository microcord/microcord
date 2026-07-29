package com.discord.widgets.nux

import android.os.Parcel
import android.os.Parcelable

data class GuildTemplateArgs(
    val title: String,
    val subtitle: String,
    val templates: List<GuildTemplate> = GuildTemplateTypes.NUX,
    val showInvitePrompt: Boolean = true,
    val skipCreationIntent: Boolean = false,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        title = parcel.readString().orEmpty(),
        subtitle = parcel.readString().orEmpty(),
        templates = parcel.createStringArrayList().orEmpty().map(GuildTemplate::valueOf),
        showInvitePrompt = parcel.readInt() != 0,
        skipCreationIntent = parcel.readInt() != 0,
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(title)
        parcel.writeString(subtitle)
        parcel.writeStringList(templates.map(GuildTemplate::name))
        parcel.writeInt(if (showInvitePrompt) 1 else 0)
        parcel.writeInt(if (skipCreationIntent) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<GuildTemplateArgs> {
        override fun createFromParcel(parcel: Parcel) = GuildTemplateArgs(parcel)

        override fun newArray(size: Int): Array<GuildTemplateArgs?> = arrayOfNulls(size)
    }
}
