package com.discord.widgets.directories

import android.os.Parcel
import android.os.Parcelable
import com.discord.models.hubs.DirectoryEntryCategory

/**
 * Which category page a [WidgetDirectoryCategory] shows.
 *
 * Parcelled by enum *name*, not ordinal, exactly as the reference does — several categories share a
 * [DirectoryEntryCategory.key], so a key would not round-trip.
 */
data class DirectoryCategoryArgs(val directoryCategory: DirectoryEntryCategory) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(directoryCategory.name)
    }

    companion object CREATOR : Parcelable.Creator<DirectoryCategoryArgs> {
        override fun createFromParcel(parcel: Parcel): DirectoryCategoryArgs =
            DirectoryCategoryArgs(DirectoryEntryCategory.valueOf(requireNotNull(parcel.readString())))

        override fun newArray(size: Int): Array<DirectoryCategoryArgs?> = arrayOfNulls(size)
    }
}
