package com.discord.utilities.rest

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.CancellationException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

class AttachmentRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val size: Long = -1L,
    private val mediaType: MediaType? = contentResolver.getType(uri)?.toMediaTypeOrNull(),
) : RequestBody(), AutoCloseable {
    @Volatile private var cancelled = false
    @Volatile private var activeStream: InputStream? = null

    override fun contentLength() = size

    override fun contentType() = mediaType

    override fun writeTo(sink: BufferedSink) {
        if (cancelled) throw CancellationException("Attachment upload was cancelled")
        val input = contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Attachment content is unavailable")
        activeStream = input
        try {
            if (cancelled) throw CancellationException("Attachment upload was cancelled")
            input.source().use(sink::writeAll)
        } finally {
            activeStream = null
            input.close()
        }
    }

    override fun close() {
        cancelled = true
        runCatching { activeStream?.close() }
    }

    override fun toString() = "AttachmentRequestBody(size=$size, contentType=$mediaType)"
}
