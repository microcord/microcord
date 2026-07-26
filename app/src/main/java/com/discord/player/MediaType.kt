package com.discord.player

import android.os.Parcel
import android.os.Parcelable

/**
 * What kind of moving media a [MediaSource] points at.
 *
 * The distinction is not about container format — both are played by the same progressive pipeline —
 * it is about presentation: a [GIFV] is a silent, looping decoration, a [VIDEO] is content the user
 * chose to watch and therefore plays once, audibly, with transport controls.
 *
 * Declaration order is `VIDEO, GIFV`, taken from the reference's `$VALUES` array rather than from
 * the decompiler's alphabetised field listing.
 */
enum class MediaType : Parcelable {
    VIDEO,
    GIFV,
    ;

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<MediaType> = object : Parcelable.Creator<MediaType> {
            override fun createFromParcel(parcel: Parcel): MediaType =
                valueOf(checkNotNull(parcel.readString()) { "MediaType name missing from parcel." })

            override fun newArray(size: Int): Array<MediaType?> = arrayOfNulls(size)
        }
    }
}
