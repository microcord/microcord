package com.discord.widgets.directories

import android.os.Parcel
import android.os.Parcelable

/** The directory channel [WidgetDirectoriesSearch] searches within. */
data class DirectoriesSearchArgs(val channelId: Long) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(channelId)
    }

    companion object CREATOR : Parcelable.Creator<DirectoriesSearchArgs> {
        override fun createFromParcel(parcel: Parcel) = DirectoriesSearchArgs(parcel.readLong())

        override fun newArray(size: Int): Array<DirectoriesSearchArgs?> = arrayOfNulls(size)
    }
}
