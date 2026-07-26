package com.discord.dialogs

import android.os.Parcel
import android.os.Parcelable

/**
 * Text for a [SimpleConfirmationDialog].
 *
 * Every field is nullable and defaults to `null`: a caller that only wants a title and a confirm
 * button leaves the rest out, and the dialog hides the empty views. Parcelable rather than
 * Serializable so it can ride in the fragment arguments bundle across process death.
 */
data class SimpleConfirmationDialogArgs(
    val title: String? = null,
    val description: String? = null,
    val positiveButtonText: String? = null,
    val negativeButtonText: String? = null,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        title = parcel.readString(),
        description = parcel.readString(),
        positiveButtonText = parcel.readString(),
        negativeButtonText = parcel.readString(),
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(title)
        parcel.writeString(description)
        parcel.writeString(positiveButtonText)
        parcel.writeString(negativeButtonText)
    }

    companion object CREATOR : Parcelable.Creator<SimpleConfirmationDialogArgs> {
        override fun createFromParcel(parcel: Parcel) = SimpleConfirmationDialogArgs(parcel)

        override fun newArray(size: Int): Array<SimpleConfirmationDialogArgs?> = arrayOfNulls(size)
    }
}
