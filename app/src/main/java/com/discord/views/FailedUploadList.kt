package com.discord.views

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.discord.R
import com.discord.api.message.LocalAttachment
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

class FailedUploadList @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val uploadViews: List<FailedUploadView>

    init {
        LayoutInflater.from(context).inflate(R.layout.view_chat_upload_list, this, true)
        uploadViews = listOf(
            requireNotNull(findViewById(R.id.chat_upload_1)),
            requireNotNull(findViewById(R.id.chat_upload_2)),
            requireNotNull(findViewById(R.id.chat_upload_3)),
        )
    }

    fun setUp(localAttachments: List<LocalAttachment>) {
        uploadViews.forEach { it.visibility = View.GONE }
        if (localAttachments.isEmpty()) return

        val uploads = localAttachments.map { attachment ->
            val uri = Uri.parse(attachment.uriString)
            Upload(attachment.displayName, fileSize(uri), context.contentResolver.getType(uri).orEmpty())
        }
        if (uploads.size <= uploadViews.size) {
            uploads.forEachIndexed { index, upload ->
                uploadViews[index].apply {
                    visibility = View.VISIBLE
                    configure(upload.displayName, iconFor(upload.mimeType), sizeSubtitle(upload.sizeBytes))
                    contentDescription = listOfNotNull(upload.displayName, sizeSubtitle(upload.sizeBytes))
                        .joinToString(", ")
                }
            }
        } else {
            uploadViews.first().apply {
                visibility = View.VISIBLE
                val title = resources.getQuantityString(
                    R.plurals.uploading_files_failed_count,
                    uploads.size,
                ).replace("{count}", uploads.size.toString())
                val subtitle = sizeSubtitle(uploads.sumOf(Upload::sizeBytes))
                configure(title, themedDrawable(R.attr.ic_uploads_file), subtitle)
                contentDescription = listOfNotNull(title, subtitle).joinToString(", ")
            }
        }
    }

    private fun fileSize(uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0 } else null
        } ?: context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    private fun iconFor(mimeType: String) = themedDrawable(
        when {
            mimeType.startsWith("image") -> R.attr.ic_uploads_image
            mimeType.startsWith("video") -> R.attr.ic_uploads_video
            else -> R.attr.ic_uploads_generic
        },
    )

    private fun themedDrawable(attribute: Int): Int = TypedValue().let {
        context.theme.resolveAttribute(attribute, it, true)
        it.resourceId
    }

    internal data class Upload(val displayName: String, val sizeBytes: Long, val mimeType: String)

    internal companion object {
        fun sizeSubtitle(bytes: Long): String? {
            if (bytes == -1L) return null
            if (bytes < 1024) return "$bytes B"
            val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
            return String.format(
                Locale.getDefault(),
                "%.1f %sB",
                bytes / 1024.0.pow(exponent.toDouble()),
                "KMGTPE"[exponent - 1],
            )
        }
    }
}
