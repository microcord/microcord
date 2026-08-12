package com.discord.api.message.attachment

import java.util.Locale
import java.net.URLConnection

/** Filename suffixes the client renders inline as an image. */
val IMAGE_MIME_TYPES: List<String> = listOf(".jpeg", ".jpg", ".gif", ".png", ".bmp", ".webp", ".avif")

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
    val contentType: String? = null,
    val durationSecs: Double? = null,
    val waveform: String? = null,
    val description: String? = null,
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

    val isRemix: Boolean
        get() = flags?.and(FLAG_IS_REMIX) == FLAG_IS_REMIX

    /**
     * Whether the proxy has to be told to keep every frame.
     *
     * Only meaningful for the two formats that can hold an animation without announcing it in the
     * extension; a GIF is animated by definition and is fetched untouched.
     */
    val isAnimated: Boolean
        get() = flags?.and(FLAG_IS_ANIMATED) == FLAG_IS_ANIMATED

    val isAvif: Boolean
        get() = filename.endsWith(".avif", ignoreCase = true)

    val isAnimatableStill: Boolean
        get() = isAnimated && (isAvif || filename.endsWith(".webp", ignoreCase = true))

    val isAudio: Boolean
        get() = contentType?.startsWith("audio/", ignoreCase = true) == true ||
            URLConnection.guessContentTypeFromName(filename)?.startsWith("audio/") == true

    companion object {
        const val SPOILER_PREFIX = "SPOILER_"
        const val FLAG_IS_REMIX = 1 shl 2
        const val FLAG_IS_SPOILER = 1 shl 3

        const val FLAG_CONTAINS_EXPLICIT_MEDIA = 1 shl 4
        const val FLAG_CONTAINS_GORE_CONTENT = 1 shl 6
        const val FLAG_CONTAINS_SELF_HARM_CONTENT = 1 shl 7
        const val FLAG_IS_ANIMATED = 1 shl 5
    }
}

enum class MessageAttachmentType { VIDEO, IMAGE, FILE }
