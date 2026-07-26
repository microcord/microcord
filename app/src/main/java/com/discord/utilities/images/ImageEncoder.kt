package com.discord.utilities.images

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

private const val DATA_URL_TEMPLATE = "data:%s;base64,%s"

private const val BUFFER_SIZE = 8192

private const val DEFAULT_JPEG_QUALITY = 100

/**
 * Turns local image content into `data:` URLs, which is how avatars, guild icons and banners are
 * submitted to the API.
 *
 * Reference: `com/discord/utilities/images/ImageEncoder.java`.
 */
class ImageEncoder {

    fun encodeBitmapAsJpegDataUrl(bitmap: Bitmap, quality: Int = DEFAULT_JPEG_QUALITY): String {
        val jpeg = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)
        val encoded = Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
        return String.format(DATA_URL_TEMPLATE, "image/jpeg", encoded)
    }

    @Throws(IOException::class)
    fun getBase64EncodedString(inputStream: InputStream, flags: Int): String {
        val buffer = ByteArray(BUFFER_SIZE)
        val bytes = ByteArrayOutputStream()
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            bytes.write(buffer, 0, read)
        }
        return Base64.encodeToString(bytes.toByteArray(), flags)
    }

    @Throws(IOException::class)
    fun getDataUrl(mimeType: String, inputStream: InputStream): String =
        String.format(
            DATA_URL_TEMPLATE,
            mimeType,
            getBase64EncodedString(inputStream, Base64.NO_WRAP),
        )
}
