package com.discord.utilities.io

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import java.net.URI
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

sealed interface UserDownloadStartResult {
    data class Enqueued(val requestId: Long) : UserDownloadStartResult
    data object WritePermissionRequired : UserDownloadStartResult
    data object InvalidUrl : UserDownloadStartResult
    data object DownloadServiceUnavailable : UserDownloadStartResult
}

sealed interface UserDownloadResult {
    data class Success(val requestId: Long, val title: String, val localUri: String) : UserDownloadResult
    data class Failure(val requestId: Long, val reason: Reason) : UserDownloadResult {
        enum class Reason { SYSTEM_FAILED, NOT_FOUND, INCOMPLETE, MISSING_LOCAL_URI, QUERY_FAILED }
    }
}

object UserDownloadManager {
    private val callbacks = ConcurrentHashMap<Long, (UserDownloadResult) -> Unit>()

    @MainThread
    fun enqueue(
        context: Context,
        url: String,
        fileName: String?,
        mimeType: String? = null,
        title: String? = null,
        description: String? = null,
        callback: (UserDownloadResult) -> Unit = {},
    ): UserDownloadStartResult {
        if (!isValidDownloadUrl(url)) return UserDownloadStartResult.InvalidUrl
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) return UserDownloadStartResult.WritePermissionRequired

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return UserDownloadStartResult.DownloadServiceUnavailable
        val safeName = sanitizeDownloadFileName(fileName ?: URI(url).path.substringAfterLast('/'))
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title ?: safeName)
            .setDescription(description)
            .setMimeType(mimeType ?: URLConnection.guessContentTypeFromName(safeName) ?: "application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) allowMediaScanning(request)

        val requestId = runCatching { manager.enqueue(request) }.getOrNull()
            ?: return UserDownloadStartResult.DownloadServiceUnavailable
        callbacks[requestId] = callback
        return UserDownloadStartResult.Enqueued(requestId)
    }

    internal fun handleCompletion(context: Context, requestId: Long) {
        val callback = callbacks.remove(requestId) ?: return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        val result = if (manager == null) {
            UserDownloadResult.Failure(requestId, UserDownloadResult.Failure.Reason.QUERY_FAILED)
        } else runCatching {
            manager.query(DownloadManager.Query().setFilterById(requestId))?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    UserDownloadResult.Failure(requestId, UserDownloadResult.Failure.Reason.NOT_FOUND)
                } else {
                    mapDownloadResult(
                        requestId = requestId,
                        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        title = cursor.stringOrEmpty(DownloadManager.COLUMN_TITLE),
                        localUri = cursor.stringOrNull(DownloadManager.COLUMN_LOCAL_URI),
                    )
                }
            } ?: UserDownloadResult.Failure(requestId, UserDownloadResult.Failure.Reason.QUERY_FAILED)
        }.getOrElse {
            UserDownloadResult.Failure(requestId, UserDownloadResult.Failure.Reason.QUERY_FAILED)
        }
        runCatching { callback(result) }
    }

    @Suppress("DEPRECATION")
    private fun allowMediaScanning(request: DownloadManager.Request) {
        request.allowScanningByMediaScanner()
    }
}

class UserDownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val requestId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (requestId >= 0) UserDownloadManager.handleCompletion(context, requestId)
    }
}

fun isValidDownloadUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

fun sanitizeDownloadFileName(fileName: String): String {
    val leaf = fileName.substringAfterLast('/').substringAfterLast('\\')
    val safe = leaf.map { if (it.code in 32..126 && it !in "<>:\"/\\|?*") it else '_' }
        .joinToString("").trim('.', ' ').ifBlank { "file" }
    if (safe.length <= 100) return safe
    val dot = safe.lastIndexOf('.')
    val extension = if (dot in 1 until safe.lastIndex) safe.substring(dot).take(20) else ""
    return safe.take(100 - extension.length) + extension
}

internal fun mapDownloadResult(
    requestId: Long,
    status: Int,
    title: String,
    localUri: String?,
): UserDownloadResult = when {
    status == DownloadManager.STATUS_SUCCESSFUL && localUri != null ->
        UserDownloadResult.Success(requestId, title, localUri)
    status == DownloadManager.STATUS_SUCCESSFUL ->
        UserDownloadResult.Failure(requestId, UserDownloadResult.Failure.Reason.MISSING_LOCAL_URI)
    status == DownloadManager.STATUS_FAILED ->
        UserDownloadResult.Failure(requestId, UserDownloadResult.Failure.Reason.SYSTEM_FAILED)
    else -> UserDownloadResult.Failure(requestId, UserDownloadResult.Failure.Reason.INCOMPLETE)
}

private fun android.database.Cursor.stringOrNull(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun android.database.Cursor.stringOrEmpty(column: String): String = stringOrNull(column).orEmpty()
