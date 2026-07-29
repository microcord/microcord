package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

data class RadioSelectorItems(val title: String, val items: List<RadioSelectorItem>) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(title)
        parcel.writeInt(items.size)
        items.forEach { it.writeToParcel(parcel, 0) }
    }

    companion object CREATOR : Parcelable.Creator<RadioSelectorItems> {
        override fun createFromParcel(parcel: Parcel): RadioSelectorItems {
            val title = parcel.readString().orEmpty()
            val size = parcel.readInt()
            return RadioSelectorItems(title, List(size.coerceAtLeast(0)) { RadioSelectorItem.createFromParcel(parcel) })
        }

        override fun newArray(size: Int): Array<RadioSelectorItems?> = arrayOfNulls(size)
    }
}
