package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class RadioSelectorItem(val id: Int, val text: String, val selected: Boolean) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readInt(), parcel.readString().orEmpty(), parcel.readInt() != 0)
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeString(text)
        parcel.writeInt(if (selected) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<RadioSelectorItem> {
        override fun createFromParcel(parcel: Parcel) = RadioSelectorItem(parcel)
        override fun newArray(size: Int): Array<RadioSelectorItem?> = arrayOfNulls(size)
    }
}
