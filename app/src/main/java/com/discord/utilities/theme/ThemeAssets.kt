package com.discord.utilities.theme

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.discord.api.attachment.RefreshAttachmentUrlsBody
import com.discord.utilities.attachments.AttachmentUrlRefresher
import com.discord.utilities.rest.RestAPI
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The files a theme points at: fonts, sounds and the background image.
 *
 * Aliucord themes name them by `https://` link or `file://` path. Links are downloaded once into
 * `filesDir/themes/assets`, keyed by the link, and never fetched at startup: a theme whose files
 * are not here yet simply goes without them until [fetchMissing] has run. Files picked in the
 * editor are copied in ([import]) and referenced by a `file://` path to the copy.
 */
object ThemeAssets {
    private const val DIR = "themes/assets"
    private const val MAX_BYTES = 25 shl 20
    private const val HTTPS = "https://"
    private const val FILE = "file://"
    private const val ATTACHMENT_EXPIRY = "ex="
    private const val PARALLEL_DOWNLOADS = 4
    private const val SIGN_BATCH = 50

    fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    /** Every file reference in [theme]: fonts, sounds and the background. */
    fun references(theme: ThemeDefinition): List<String> =
        theme.fonts.values + theme.raws.values + listOfNotNull(theme.background?.url)

    /** The local file for [reference], or `null` when it is not available (yet). */
    fun local(context: Context, reference: String): File? = when {
        reference.startsWith(HTTPS) -> downloaded(context, reference).takeIf { it.isFile }
        // Only files picked in the editor: a shared theme must not be able to read any app file.
        reference.startsWith(FILE) -> File(reference.removePrefix(FILE)).canonicalFile
            .takeIf { it.parentFile == dir(context).canonicalFile && it.isFile && it.length() <= MAX_BYTES }
        else -> null
    }

    /**
     * Downloads every link in [themes] that is not here yet; returns the links that failed.
     *
     * Discord links are re-signed in one request, then up to [PARALLEL_DOWNLOADS] files download
     * at once.
     */
    @WorkerThread
    fun fetchMissing(context: Context, themes: List<ThemeDefinition>): List<String> {
        val urls = themes.flatMap(::references).filter { it.startsWith(HTTPS) && local(context, it) == null }.distinct()
        if (urls.isEmpty()) return emptyList()
        val signed = signAll(urls)
        val pool = Executors.newFixedThreadPool(minOf(PARALLEL_DOWNLOADS, urls.size))
        try {
            return urls.map { url ->
                url to pool.submit<Boolean> { runCatching { store(downloaded(context, url), download(signed.getValue(url))) }.isSuccess }
            }.filterNot { (_, done) -> done.get() }.map { it.first }
        } finally {
            pool.shutdown()
        }
    }

    /** Copies [uri] into the asset folder; returns the `file://` reference for a theme. */
    @WorkerThread
    fun import(context: Context, uri: Uri, extension: String): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
            ?: throw IOException("The file could not be opened")
        val target = File(dir(context), "file-" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)) + extension)
        store(target, bytes)
        return FILE + target.absolutePath
    }

    /**
     * Downloads [url], at most [maxBytes] of it.
     *
     * Discord attachment links are re-signed first. The CDN refuses them without a current
     * signature, and that is where most older Aliucord themes host their files.
     */
    @WorkerThread
    fun fetch(url: String, maxBytes: Int = MAX_BYTES): ByteArray = download(signAll(listOf(url)).getValue(url), maxBytes)

    private fun download(url: String, maxBytes: Int = MAX_BYTES): ByteArray {
        require(url.startsWith(HTTPS)) { "Only https links are allowed" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { readBounded(it, maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    /** Reads [input] to the end, refusing more than [maxBytes]. */
    fun readBounded(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) throw IOException("The file is larger than ${maxBytes shr 20} MB")
        }
    }

    /** The download target for [url], named after the link so one link is fetched once. */
    private fun downloaded(context: Context, url: String): File {
        val name = Uri.parse(url).lastPathSegment.orEmpty()
        val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        return File(dir(context), "url-" + hex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray())) +
            (extension?.let { ".$it" } ?: ""))
    }

    /** Temp file plus rename, so a half-written file is never taken for a finished one. */
    private fun store(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Could not store ${target.name}" }
        }
    }

    /**
     * Every URL mapped to one the CDN accepts. Discord attachment links need a current signature,
     * and that is where most older Aliucord themes host their files; the API re-signs those, up to
     * [SIGN_BATCH] per request. A link it will not re-sign is tried as it is.
     */
    private fun signAll(urls: List<String>): Map<String, String> {
        val now = System.currentTimeMillis()
        val stale = urls.filter {
            AttachmentUrlRefresher.isSignedAttachment(it) &&
                (!it.contains(ATTACHMENT_EXPIRY) || AttachmentUrlRefresher.isExpired(it, now))
        }
        val api = RestAPI.apiOrNull
        val refreshed = if (stale.isEmpty() || api == null) emptyMap() else stale.chunked(SIGN_BATCH).flatMap { batch ->
            runCatching { api.refreshAttachmentUrls(RefreshAttachmentUrlsBody(batch)).toBlocking().first().refreshedUrls }
                .getOrDefault(emptyList())
        }.associate { it.original to it.refreshed }
        return urls.associateWith { refreshed[it] ?: it }
    }

    private fun hex(bytes: ByteArray) =
        bytes.take(12).joinToString("") { Integer.toHexString((it.toInt() and 0xFF) or 0x100).substring(1) }
}
