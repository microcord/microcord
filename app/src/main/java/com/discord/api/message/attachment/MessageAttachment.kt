package com.discord.api.message.attachment

import java.util.Locale

/** Filename suffixes the client renders inline as an image. */
val IMAGE_MIME_TYPES: List<String> = listOf(".jpeg", ".jpg", ".gif", ".png", ".bmp", ".webp")

/** Filename suffixes the client renders inline as a video. */
val VIDEO_MIME_TYPES: List<String> = listOf(".webm", ".mov", ".mpeg", ".mpg", ".mp4")

data class MessageAttachment(
    val url: String,
    val size: Long,
    val id: Long,
    val proxyUrl: String,
    val filename: String,
    val width: Int?,
    val height: Int?,
    val flags: Int? = null,
) {
    val type: MessageAttachmentType
        get() {
            val path = url.lowercase(Locale.ROOT)
            return when {
                IMAGE_MIME_TYPES.any(path::contains) -> MessageAttachmentType.IMAGE
                VIDEO_MIME_TYPES.any(path::contains) -> MessageAttachmentType.VIDEO
                else -> MessageAttachmentType.FILE
            }
        }

    val isSpoiler: Boolean
        get() = filename.startsWith(SPOILER_PREFIX) || flags?.and(FLAG_IS_SPOILER) == FLAG_IS_SPOILER

    companion object {
        const val SPOILER_PREFIX = "SPOILER_"
        const val FLAG_IS_SPOILER = 1 shl 3
    }
}

enum class MessageAttachmentType { VIDEO, IMAGE, FILE }
