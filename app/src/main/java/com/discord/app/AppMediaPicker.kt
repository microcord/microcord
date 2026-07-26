package com.discord.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The picker flows a screen can start, identified by their `startActivityForResult` request code. */
enum class MediaPickerRequest(val requestCode: Int) {
    CAMERA(777),
    GALLERY(778),
    DOCUMENTS(779),
    CROP(800),
    CHOOSER(801);

    companion object {
        fun fromRequestCode(requestCode: Int): MediaPickerRequest? =
            values().firstOrNull { it.requestCode == requestCode }
    }
}

/**
 * Resolves media picker activity results back into content URIs.
 *
 * Camera captures do not come back in the result intent: the capture URI is handed to the camera
 * app up front and stashed in a dedicated preference so it survives the process death that a
 * camera activity can cause.
 */
object AppMediaPicker {

    private const val PREFS_NAME = "picker"
    private const val KEY_PENDING_CAPTURE_URI = "picker_uri"
    private const val CROP_OUTPUT_EXTRA = "com.yalantis.ucrop.OutputUri"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    /** Result of a picker flow, delivered to the requesting screen. */
    sealed interface Result {
        data class Picked(val uri: Uri, val request: MediaPickerRequest) : Result
        data class Failed(val cause: Exception) : Result
        data object Cancelled : Result
    }

    /**
     * Interprets `onActivityResult` for the picker request codes.
     *
     * Returns `null` when [requestCode] does not belong to a picker flow, so callers can keep
     * handling their own request codes.
     */
    fun handleActivityResult(context: Context, requestCode: Int, resultCode: Int, data: Intent?): Result? {
        val request = MediaPickerRequest.fromRequestCode(requestCode) ?: return null
        return when (resultCode) {
            android.app.Activity.RESULT_CANCELED -> Result.Cancelled
            android.app.Activity.RESULT_OK -> try {
                Result.Picked(resolveUri(context, request, data), request)
            } catch (e: IOException) {
                Result.Failed(e)
            }
            else -> Result.Failed(IOException("Bad activity result code: $resultCode, for request code: $requestCode"))
        }
    }

    /** Builds the chooser intent that offers both a camera capture and any gallery/document source. */
    @Throws(IOException::class)
    fun createChooserIntent(context: Context, chooserTitle: CharSequence, mimeType: String, captureUri: Uri): Intent {
        val pick = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
        val capture = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, captureUri)
        return Intent.createChooser(pick, chooserTitle)
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(capture))
    }

    /**
     * Registers [captureUri] as the destination of the next camera capture and grants every camera
     * app that can serve [captureIntent] permission to write to it.
     */
    fun prepareCapture(context: Context, captureIntent: Intent, captureUri: Uri) {
        setPendingCaptureUri(context, captureUri.toString())
        context.packageManager
            .queryIntentActivities(captureIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .forEach { resolveInfo ->
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    captureUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
    }

    /** Creates a shareable URI for a capture destination and records it as pending. */
    @Throws(IOException::class)
    fun createCaptureUri(context: Context, file: File): Uri {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        setPendingCaptureUri(context, uri.toString())
        return uri
    }

    /** Allocates the file a camera capture writes into. */
    fun createCaptureFile(context: Context): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), context.packageName)
            .takeIf { it.mkdirs() || it.exists() }
            ?: context.filesDir
        return File(directory, "IMG_$timestamp.jpg")
    }

    @Throws(IOException::class)
    private fun resolveUri(context: Context, request: MediaPickerRequest, data: Intent?): Uri = when (request) {
        MediaPickerRequest.CAMERA -> consumePendingCaptureUri(context)
        MediaPickerRequest.GALLERY, MediaPickerRequest.DOCUMENTS ->
            data?.data ?: throw IOException("Picker returned no data result.")
        MediaPickerRequest.CROP -> data?.getParcelableExtra(CROP_OUTPUT_EXTRA) ?: Uri.EMPTY
        // A chooser can resolve to either the gallery branch (data present) or the camera branch.
        MediaPickerRequest.CHOOSER -> data?.data ?: consumePendingCaptureUri(context)
    }

    private fun consumePendingCaptureUri(context: Context): Uri {
        val stored = preferences(context).getString(KEY_PENDING_CAPTURE_URI, null) ?: return Uri.EMPTY
        setPendingCaptureUri(context, null)
        return Uri.parse(stored)
    }

    private fun setPendingCaptureUri(context: Context, uri: String?) {
        preferences(context).edit().putString(KEY_PENDING_CAPTURE_URI, uri).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
