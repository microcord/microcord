package com.discord.widgets.chat.input

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import com.lytefast.flexinput.model.Attachment

/**
 * An [Attachment] that remembers where the user got it from, and whether it is marked as a spoiler.
 *
 * [source] is the analytics dimension for `add_attachment` — "media_picker", "camera", "files",
 * "keyboard" — and is the whole reason this subclass exists: by the time the attachment reaches the
 * preview strip, the picker that produced it is long gone.
 *
 * [spoiler] is a `var` because [com.discord.widgets.chat.input.attachments.AttachmentBottomSheet]
 * toggles it on an attachment that is already in the selection. The reference carries the same flag
 * on its own fork of `Attachment`; the published FlexInput has no such field, so it lives here and
 * the upload layer reads it when composing the `SPOILER_` filename prefix.
 */
class SourcedAttachment<T>(
    id: Long,
    uri: Uri,
    displayName: String,
    data: T? = null,
    val source: String,
    var spoiler: Boolean = false,
) : Attachment<T>(id, uri, displayName, data) {

    constructor(attachment: Attachment<T>, source: String) : this(
        attachment.id,
        attachment.uri,
        attachment.displayName,
        attachment.data,
        source,
    )

    override fun toString(): String = "SourcedAttachment(uri=$uri, data=$data)"

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)
        dest.writeString(source)
        dest.writeInt(if (spoiler) 1 else 0)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<SourcedAttachment<*>> {
            override fun createFromParcel(parcelIn: Parcel): SourcedAttachment<Any> {
                // Field order must match writeToParcel: the four Attachment fields, then ours.
                val id = parcelIn.readLong()
                val uri = parcelIn.readParcelable<Uri>(Uri::class.java.classLoader) ?: Uri.EMPTY
                val displayName = parcelIn.readString() ?: ""
                return SourcedAttachment(
                    id = id,
                    uri = uri,
                    displayName = displayName,
                    data = null,
                    source = parcelIn.readString() ?: "",
                    spoiler = parcelIn.readInt() == 1,
                )
            }

            override fun newArray(size: Int): Array<SourcedAttachment<*>?> = arrayOfNulls(size)
        }
    }
}
