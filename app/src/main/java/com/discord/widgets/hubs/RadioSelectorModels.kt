package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable

/** One row of [WidgetRadioSelectorBottomSheet]. [id] is returned to the caller when it is tapped. */
data class RadioSelectorItem(
    val id: Int,
    val text: String,
    val selected: Boolean,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        text = parcel.readString().orEmpty(),
        selected = parcel.readInt() != 0,
    )

    override fun describeContents(): Int = 0

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

/** Arguments for [WidgetRadioSelectorBottomSheet]: its header and its rows. */
data class RadioSelectorItems(
    val title: String,
    val items: List<RadioSelectorItem>,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(title)
        parcel.writeInt(items.size)
        items.forEach { it.writeToParcel(parcel, 0) }
    }

    companion object CREATOR : Parcelable.Creator<RadioSelectorItems> {
        override fun createFromParcel(parcel: Parcel): RadioSelectorItems {
            val title = parcel.readString().orEmpty()
            val size = parcel.readInt()
            val items = ArrayList<RadioSelectorItem>(size.coerceAtLeast(0))
            repeat(size) { items.add(RadioSelectorItem.createFromParcel(parcel)) }
            return RadioSelectorItems(title, items)
        }

        override fun newArray(size: Int): Array<RadioSelectorItems?> = arrayOfNulls(size)
    }
}
