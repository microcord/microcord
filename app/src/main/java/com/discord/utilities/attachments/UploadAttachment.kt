package com.discord.utilities.attachments

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.discord.api.message.LocalAttachment
import com.discord.api.message.attachment.MessageAttachment
import com.lytefast.flexinput.model.Attachment
import java.io.File
import java.io.FileNotFoundException
import java.net.URLConnection
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class UploadAttachment(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val spoiler: Boolean,
    val mimeType: String,
    val size: Long,
    val permissionPersisted: Boolean,
    val durationSecs: Double? = null,
    val waveform: String? = null,
) {
    val fileName: String = sanitizeUploadFileName(displayName)

    fun toLocalAttachment() = LocalAttachment(
        id,
        uri.toString(),
        fileName,
        mimeType,
        size,
        durationSecs,
        waveform,
    )

    companion object {
        fun from(
            resolver: ContentResolver,
            attachment: Attachment<*>,
            spoiler: Boolean = attachment.displayName.startsWith(MessageAttachment.SPOILER_PREFIX),
            persistPermission: Boolean = true,
        ): UploadAttachment = from(
            resolver,
            attachment.id,
            attachment.uri,
            attachment.displayName,
            spoiler,
            persistPermission,
        )

        fun from(
            resolver: ContentResolver,
            id: Long,
            uri: Uri,
            displayName: String,
            spoiler: Boolean = false,
            persistPermission: Boolean = true,
        ): UploadAttachment {
            val metadata = resolver.queryMetadata(uri)
            val readable = resolver.openInputStream(uri)?.use { true } ?: false
            val name = metadata.name?.takeIf(String::isNotBlank) ?: displayName
            val detectedMime = resolver.getType(uri)
                ?: URLConnection.guessContentTypeFromName(name)
            val detectedSize = metadata.size ?: resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            val (mime, size) = validateAttachmentMetadata(readable, detectedMime, detectedSize)
            val persisted = persistPermission && uri.scheme == ContentResolver.SCHEME_CONTENT && try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (_: SecurityException) {
                false
            }
            return UploadAttachment(id, uri, name, spoiler, mime, size, persisted)
        }
    }
}

fun LocalAttachment.toUploadAttachment(resolver: ContentResolver, persistPermission: Boolean = false) =
    UploadAttachment.from(
        resolver = resolver,
        id = id,
        uri = Uri.parse(uriString),
        displayName = displayName,
        spoiler = displayName.startsWith(MessageAttachment.SPOILER_PREFIX),
        persistPermission = persistPermission,
    ).copy(
        mimeType = mimeType ?: "application/octet-stream",
        size = size ?: -1L,
        durationSecs = durationSecs,
        waveform = waveform,
    )

internal data class AttachmentMetadata(val name: String?, val size: Long?)

internal fun validateAttachmentMetadata(readable: Boolean, mimeType: String?, size: Long?): Pair<String, Long> {
    if (!readable) throw FileNotFoundException("Attachment content is unavailable")
    return (mimeType ?: "application/octet-stream") to (size ?: -1L)
}

fun sanitizeUploadFileName(displayName: String, spoiler: Boolean = false): String {
    val prefix = MessageAttachment.SPOILER_PREFIX
    val withoutPrefix = displayName.removePrefix(prefix).substringAfterLast('/').substringAfterLast('\\')
    val ascii = Normalizer.normalize(withoutPrefix, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .map { if (it.code in 33..126 && it !in "<>:\"/\\|?*") it else '_' }
        .joinToString("")
        .trim('.', ' ')
        .ifBlank { "attachment" }
    val bounded = if (ascii.length <= 100) ascii else {
        val dot = ascii.lastIndexOf('.')
        val extension = if (dot in 1 until ascii.length - 1) ascii.substring(dot).take(20) else ""
        ascii.take(100 - extension.length) + extension
    }
    return if (spoiler) prefix + bounded else bounded
}

fun uniqueUploadFileNames(attachments: List<UploadAttachment>): List<String> {
    return uniqueUploadFileNamesFromSanitized(attachments.map(UploadAttachment::fileName))
}

internal fun uniqueUploadFileNamesFromSanitized(names: List<String>): List<String> {
    val used = HashSet<String>()
    return names.map { name ->
        var candidate = name
        var duplicate = 1
        while (!used.add(candidate.lowercase(Locale.ROOT))) {
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            candidate = name.substring(0, dot) + "-$duplicate" + name.substring(dot)
            duplicate++
        }
        candidate
    }
}

object UploadAttachmentPreprocessor {
    private const val MAX_DIMENSION = 4096

    fun compressImages(context: Context, attachments: List<UploadAttachment>): List<UploadAttachment> =
        attachments.map { attachment ->
            if (!attachment.mimeType.startsWith("image/") || attachment.mimeType == "image/gif") attachment
            else compressJpeg(context, attachment)
        }

    private fun compressJpeg(context: Context, attachment: UploadAttachment): UploadAttachment {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(attachment.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw FileNotFoundException("Attachment content is unavailable")
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) sample *= 2
        val bitmap = resolver.openInputStream(attachment.uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Attachment is not a decodable image")
        val output = File(context.cacheDir, "upload-${UUID.randomUUID()}.jpg")
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
        bitmap.recycle()
        return attachment.copy(
            uri = Uri.fromFile(output),
            displayName = attachment.fileName.substringBeforeLast('.', attachment.fileName) + ".jpg",
            mimeType = "image/jpeg",
            size = output.length(),
            permissionPersisted = false,
        )
    }
}

private fun ContentResolver.queryMetadata(uri: Uri): AttachmentMetadata {
    var queryCursor: Cursor? = null
    return try {
        queryCursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        val cursor = queryCursor
        if (cursor == null || !cursor.moveToFirst()) AttachmentMetadata(null, null)
        else AttachmentMetadata(
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString),
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
        )
    } catch (_: RuntimeException) {
        AttachmentMetadata(null, null)
    } finally {
        queryCursor?.close()
    }
}
