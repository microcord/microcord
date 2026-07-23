package com.discord.api.message.attachment

import java.util.Locale

data class MessageAttachment(
    val url: String,
    val size: Long,
    val id: Long,
    val proxyUrl: String,
    val filename: String,
    val width: Int?,
    val height: Int?,
) {
    val type: MessageAttachmentType
        get() {
            val path = url.lowercase(Locale.ROOT)
            return when {
                IMAGE_EXTENSIONS.any(path::contains) -> MessageAttachmentType.IMAGE
                VIDEO_EXTENSIONS.any(path::contains) -> MessageAttachmentType.VIDEO
                else -> MessageAttachmentType.FILE
            }
        }

    val isSpoiler: Boolean
        get() = filename.startsWith(SPOILER_PREFIX)

    companion object {
        const val SPOILER_PREFIX = "SPOILER_"
        private val IMAGE_EXTENSIONS = listOf(".jpeg", ".jpg", ".gif", ".png", ".bmp", ".webp")
        private val VIDEO_EXTENSIONS = listOf(".webm", ".mov", ".mpeg", ".mpg", ".mp4")
    }
}

enum class MessageAttachmentType { VIDEO, IMAGE, FILE }
