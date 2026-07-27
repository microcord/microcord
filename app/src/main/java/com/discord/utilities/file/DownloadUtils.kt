package com.discord.utilities.file

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.discord.utilities.io.sanitizeDownloadFileName
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import rx.Observable

object DownloadUtils {
    sealed class DownloadState {
        class InProgress(val progress: Float) : DownloadState()
        class Completed(val file: File) : DownloadState()
        class Failure(val exception: Exception) : DownloadState()
    }

    @JvmStatic
    @JvmOverloads
    @RequiresPermission(value = Manifest.permission.WRITE_EXTERNAL_STORAGE, conditional = true)
    fun downloadFile(
        context: Context,
        fileUrl: String,
        fileName: String,
        downloadDirectory: File = context.cacheDir,
    ): Observable<DownloadState> = Observable.create { subscriber ->
        subscriber.onNext(DownloadState.InProgress(0f))
        var connection: HttpURLConnection? = null
        var partial: File? = null
        try {
            check(downloadDirectory.exists() || downloadDirectory.mkdirs()) {
                "Unable to create download directory"
            }
            val destination = resolveDownloadFile(downloadDirectory, fileName)
            val partialFile = confinedFile(downloadDirectory, "${destination.name}.part")
            partial = partialFile
            connection = URL(fileUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${connection.responseCode}")
            }
            val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            connection.inputStream.buffered().use { input ->
                partialFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (!subscriber.isUnsubscribed) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        subscriber.onNext(
                            DownloadState.InProgress(
                                if (total > 0) downloaded.toFloat() / total else 0f,
                            ),
                        )
                    }
                }
            }
            if (subscriber.isUnsubscribed) {
                partialFile.delete()
                return@create
            }
            if (destination.exists()) destination.delete()
            check(partialFile.renameTo(destination)) { "Unable to finish download" }
            subscriber.onNext(DownloadState.Completed(destination))
            subscriber.onCompleted()
        } catch (error: Exception) {
            partial?.delete()
            if (!subscriber.isUnsubscribed) {
                subscriber.onNext(DownloadState.Failure(error))
                subscriber.onCompleted()
            }
        } finally {
            connection?.disconnect()
        }
    }

    internal fun resolveDownloadFile(downloadDirectory: File, fileName: String): File =
        confinedFile(downloadDirectory, sanitizeDownloadFileName(fileName))

    private fun confinedFile(downloadDirectory: File, fileName: String): File {
        val directory = downloadDirectory.canonicalFile
        return File(directory, fileName).canonicalFile.also {
            require(it.parentFile == directory) { "Download path escapes its directory" }
        }
    }
}
