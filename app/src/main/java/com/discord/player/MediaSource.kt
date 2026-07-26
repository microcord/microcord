package com.discord.player

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

/**
 * A single piece of progressively-streamed media, plus enough context to report a playback failure
 * against the feature that asked for it.
 *
 * It is [Parcelable] because it travels on the intent that opens the fullscreen viewer.
 *
 * @property progressiveMediaUri the URI handed to the player; always a direct media URL, never a
 *   page that embeds one.
 * @property featureTag identifies the surface that requested playback, so a decode or network
 *   failure can be attributed. The reference passes the literal `"javaClass"` from the fullscreen
 *   viewer — see [com.discord.widgets.media.WidgetMedia].
 * @property mediaType how the media should be presented; see [MediaType].
 */
data class MediaSource(
    val progressiveMediaUri: Uri,
    val featureTag: String,
    val mediaType: MediaType,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(progressiveMediaUri, flags)
        parcel.writeString(featureTag)
        parcel.writeParcelable(mediaType, flags)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<MediaSource> = object : Parcelable.Creator<MediaSource> {
            @Suppress("DEPRECATION")
            override fun createFromParcel(parcel: Parcel): MediaSource = MediaSource(
                progressiveMediaUri = checkNotNull(parcel.readParcelable(Uri::class.java.classLoader)),
                featureTag = checkNotNull(parcel.readString()),
                mediaType = checkNotNull(parcel.readParcelable(MediaType::class.java.classLoader)),
            )

            override fun newArray(size: Int): Array<MediaSource?> = arrayOfNulls(size)
        }

        /**
         * Builds a source from a raw URL.
         *
         * The reference exposes this as a top-level function next to the class; it is kept here so
         * the parse of [progressiveMediaUri] has exactly one implementation.
         */
        fun create(mediaType: MediaType, progressiveMediaUri: String, featureTag: String): MediaSource =
            MediaSource(Uri.parse(progressiveMediaUri), featureTag, mediaType)
    }
}
