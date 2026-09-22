package com.discord.media_picker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.ColorInt
import androidx.core.content.FileProvider
import com.yalantis.ucrop.UCrop
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Everything the app needs to get an image out of the device and into a screen.
 *
 * Four flows exist — camera, gallery, documents and crop — and they all arrive back through the
 * hosting screen's `onActivityResult`, which is why this object is stateless and keyed on
 * [RequestType] request codes rather than holding callbacks.
 *
 * The camera flow is the awkward one: `ACTION_IMAGE_CAPTURE` writes to a URI handed to it up front
 * and returns nothing, and taking a photo can kill the calling process. The destination URI is
 * therefore persisted to a dedicated preference file the moment it is created and read back when the
 * result arrives — see [readAndClear].
 */
object MediaPicker {

    private const val PREFS_NAME = "picker"
    private const val KEY_PENDING_CAPTURE_URI = "picker_uri"
    private const val CROP_OUTPUT_EXTRA = "com.yalantis.ucrop.OutputUri"
    private val cdnClient = OkHttpClient()

    /**
     * Authority of the app's [FileProvider].
     *
     * NOTE: the reference uses `<packageName>.file-provider`; this project's manifest declares
     * `<applicationId>.fileprovider`, so the value follows the manifest. Changing one without the
     * other makes every capture fail with `IllegalArgumentException`.
     */
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    /**
     * The screen that owns a picker flow.
     *
     * `AppFragment` satisfies all three members, which is why the reference can hand any screen to
     * `MGImages.prepareImageUpload`.
     */
    interface Provider {
        fun requireContext(): Context

        /** Destination a capture or crop is written to. */
        fun getImageFile(): File

        fun startActivityForResult(intent: Intent, requestCode: Int)
    }

    /** Receives the outcome of a picker flow. */
    interface ResultListener {
        /** The flow failed; [e] is an [IOException] for every failure this object raises itself. */
        fun onError(e: Exception)

        /** [uri] was selected or captured by [request]. */
        fun onMediaSelected(uri: Uri, request: RequestType)
    }

    /** Colours the host screen wants uCrop's chrome drawn with. */
    data class CropTheme(
        @ColorInt val toolbarColor: Int,
        @ColorInt val statusBarColor: Int,
        @ColorInt val toolbarWidgetColor: Int,
        @ColorInt val activeControlsColor: Int,
    )


    /**
     * Downloads a public CDN image into app-private storage for the normal crop/upload pipeline.
     * A temporary sibling prevents a failed transfer from leaving a partial picker input behind.
     */
    fun downloadRemoteImage(url: String, destination: File): Observable<Uri> =
        Observable.fromCallable {
            val partial = File(destination.parentFile, "${destination.name}.part")
            partial.delete()
            try {
                cdnClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Image download failed with HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Image download returned no body")
                    partial.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
                    if (destination.exists() && !destination.delete()) {
                        throw IOException("Unable to replace image download")
                    }
                    if (!partial.renameTo(destination)) throw IOException("Unable to store image download")
                    Uri.fromFile(destination)
                }
            } finally {
                partial.delete()
            }
        }.subscribeOn(Schedulers.io())

    /**
     * Opens the combined image chooser — camera, gallery and document providers in one sheet.
     *
     * A capture destination is allocated and registered before the chooser opens, because the sheet
     * may launch the camera and the camera needs somewhere to write.
     */
    fun openMediaChooser(
        provider: Provider,
        chooserTitle: CharSequence?,
        mimeType: String,
        onError: (IOException) -> Unit = {},
    ) {
        try {
            val context = provider.requireContext()
            val captureUri = createCaptureUri(provider)
            val chooser =
                buildImageChooserIntent(context.packageManager, chooserTitle, captureUri, mimeType)
            // Only the camera branch needs write access; grant it before the sheet appears.
            grantUriPermissions(context, Intent(MediaStore.ACTION_IMAGE_CAPTURE), captureUri)
            startActivityForResult(provider, chooser, RequestType.CHOOSER)
        } catch (e: IOException) {
            onError(e)
        }
    }

    /**
     * Launches uCrop for [inputUri], writing the result to the provider's image file.
     *
     * @param maxOutputDimensionPx clamped to at least [MIN_OUTPUT_DIMENSION_PX] — uCrop treats a
     *   smaller bound as "unset" and would hand back the full-size image.
     */
    fun requestImageCrop(
        provider: Provider,
        inputUri: Uri,
        theme: CropTheme,
        aspectRatioX: Float = 1f,
        aspectRatioY: Float = 1f,
        maxOutputDimensionPx: Int = DEFAULT_MAX_OUTPUT_DIMENSION_PX,
        onError: (IOException) -> Unit = {},
    ) {
        try {
            val context = provider.requireContext()
            val destinationUri = Uri.fromFile(provider.getImageFile())
            val maxDimension = maxOutputDimensionPx.coerceAtLeast(MIN_OUTPUT_DIMENSION_PX)
            val intent = UCrop.of(inputUri, destinationUri)
                .withOptions(cropOptions(theme))
                .withMaxResultSize(maxDimension, maxDimension)
                .withAspectRatio(aspectRatioX, aspectRatioY)
                .getIntent(context)
                .setClass(context, ProfileCropActivity::class.java)

            // uCrop writes through a file URI, so every activity that can serve the intent needs
            // read/write access to the destination.
            grantUriPermissions(context, intent, destinationUri)
            startActivityForResult(provider, intent, RequestType.CROP)
        } catch (e: IOException) {
            onError(e)
        }
    }

    /**
     * Turns an `onActivityResult` callback into a [ResultListener] call.
     *
     * Does nothing when [requestCode] does not belong to a picker flow, so the caller can keep
     * handling its own request codes.
     */
    fun handleActivityResult(
        context: Context,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        result: ResultListener,
    ) {
        val requestType = RequestType.fromCode(requestCode) ?: return
        try {
            when (resultCode) {
                Activity.RESULT_OK -> {
                    val uri = getResultUri(context, requestType, data)
                    // A capture written through the file provider is invisible to the gallery until
                    // the media scanner has seen it.
                    context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                    result.onMediaSelected(uri, requestType)
                }

                Activity.RESULT_CANCELED -> Unit

                else -> throw IOException(
                    "Bad activity result code: $resultCode, for request code: $requestCode",
                )
            }
        } catch (e: IOException) {
            result.onError(e)
        }
    }

    /**
     * Allocates a shareable URI for the provider's image file and records it as the destination of
     * the next capture.
     */
    @Throws(IOException::class)
    fun createCaptureUri(provider: Provider): Uri {
        val file = provider.getImageFile()
        val context = provider.requireContext()
        val authority = context.packageName + FILE_PROVIDER_SUFFIX
        val uri = FileProvider.getUriForFile(context, authority, file)
        // Stored as a file URI, not the content URI: that is what the reference persists, and it
        // survives the provider's grants being revoked when the process dies.
        setPendingCaptureUri(context, file.toURI().toString())
        return uri
    }

    /**
     * Returns the pending capture URI and clears it, or [Uri.EMPTY] when no capture is pending.
     *
     * Clearing on read is what stops a stale capture from being handed to the next picker result.
     */
    fun readAndClear(context: Context): Uri {
        val stored = preferences(context).getString(KEY_PENDING_CAPTURE_URI, null)
            ?: return Uri.EMPTY
        setPendingCaptureUri(context, null)
        return Uri.parse(stored)
    }

    /** Grants every activity that can serve [intent] read/write access to [uri]. */
    fun grantUriPermissions(context: Context, intent: Intent, uri: Uri) {
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .forEach { resolveInfo ->
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
    }

    /**
     * The URI a completed [request] produced.
     *
     * Each flow reports its result differently, which is the whole reason this function exists.
     */
    @Throws(IOException::class)
    fun getResultUri(context: Context, request: RequestType, data: Intent?): Uri = when (request) {
        // The camera returns nothing; the destination was decided before it launched.
        RequestType.CAMERA -> readAndClear(context)

        RequestType.GALLERY, RequestType.DOCUMENTS ->
            data?.data ?: throw IOException("Picker returned no data result.")

        RequestType.CROP -> data?.getParcelableExtra(CROP_OUTPUT_EXTRA) ?: Uri.EMPTY

        // A chooser resolves to either branch, so fall back to the capture URI when there is no data.
        RequestType.CHOOSER -> data?.data ?: readAndClear(context)
    }

    @Deprecated(
        "Renamed to match the reference module.",
        ReplaceWith("requestImageCrop(provider, inputUri, theme, aspectRatioX, aspectRatioY, maxOutputDimensionPx, onError)"),
    )
    fun requestCrop(
        provider: Provider,
        inputUri: Uri,
        theme: CropTheme,
        aspectRatioX: Float = 1f,
        aspectRatioY: Float = 1f,
        maxOutputDimensionPx: Int = DEFAULT_MAX_OUTPUT_DIMENSION_PX,
        onError: (IOException) -> Unit = {},
    ) = requestImageCrop(
        provider = provider,
        inputUri = inputUri,
        theme = theme,
        aspectRatioX = aspectRatioX,
        aspectRatioY = aspectRatioY,
        maxOutputDimensionPx = maxOutputDimensionPx,
        onError = onError,
    )

    /**
     * Starts [intent] for [request]'s code, translating "no app can do this" into an [IOException]
     * so every failure in a picker flow arrives on the same path.
     */
    @Throws(IOException::class)
    private fun startActivityForResult(provider: Provider, intent: Intent, request: RequestType) {
        try {
            provider.startActivityForResult(intent, request.code)
        } catch (e: ActivityNotFoundException) {
            throw IOException("No application available for media picker.", e)
        }
    }

    private fun cropOptions(theme: CropTheme): UCrop.Options = UCrop.Options().apply {
        setToolbarColor(theme.toolbarColor)
        setStatusBarColor(theme.statusBarColor)
        setToolbarWidgetColor(theme.toolbarWidgetColor)
        setActiveControlsWidgetColor(theme.activeControlsColor)
        setCropFrameColor(theme.activeControlsColor)
        setCropGridColor(theme.activeControlsColor)
        // PNG keeps transparency, which matters for emoji and role icons.
        setCompressionFormat(Bitmap.CompressFormat.PNG)
    }

    private fun setPendingCaptureUri(context: Context, uri: String?) {
        preferences(context).edit().putString(KEY_PENDING_CAPTURE_URI, uri).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val DEFAULT_MAX_OUTPUT_DIMENSION_PX = 1024
    private const val MIN_OUTPUT_DIMENSION_PX = 10
}
