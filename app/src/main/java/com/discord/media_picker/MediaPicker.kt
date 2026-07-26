package com.discord.media_picker

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.ColorInt
import com.discord.app.MediaPickerRequest
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.IOException

/**
 * The crop half of the reference's `com.discord.media_picker` module.
 *
 * Everything else that module does — building the chooser intent, granting the camera app write
 * access to the capture URI and resolving an activity result back into a content URI — is already
 * reconstructed as [com.discord.app.AppMediaPicker], so only the pieces with no counterpart there
 * live here: the [Provider] a screen implements to receive the crop result, and the uCrop launch.
 */
object MediaPicker {

    /**
     * The screen that owns a picker flow.
     *
     * `AppFragment` satisfies all three members, which is why the reference can hand any screen to
     * `MGImages.prepareImageUpload`.
     */
    interface Provider {
        fun requireContext(): Context

        /** Destination the cropped image is written to. */
        fun getImageFile(): File

        fun startActivityForResult(intent: Intent, requestCode: Int)
    }

    /** Colours the host screen wants uCrop's chrome drawn with. */
    data class CropTheme(
        @ColorInt val toolbarColor: Int,
        @ColorInt val statusBarColor: Int,
        @ColorInt val toolbarWidgetColor: Int,
        @ColorInt val activeControlsColor: Int,
    )

    /**
     * Launches uCrop for [inputUri], writing the result to the provider's image file.
     *
     * @param maxOutputDimensionPx clamped to at least 10px, matching the reference — uCrop treats a
     *   smaller bound as "unset" and would return the full-size image.
     */
    fun requestCrop(
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

            // uCrop writes through a file URI, so every activity that can serve the intent needs
            // read/write access to the destination.
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { resolveInfo ->
                    context.grantUriPermission(
                        resolveInfo.activityInfo.packageName,
                        destinationUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }

            try {
                provider.startActivityForResult(intent, MediaPickerRequest.CROP.requestCode)
            } catch (e: ActivityNotFoundException) {
                throw IOException("No application available for media picker.", e)
            }
        } catch (e: IOException) {
            onError(e)
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

    private const val DEFAULT_MAX_OUTPUT_DIMENSION_PX = 1024
    private const val MIN_OUTPUT_DIMENSION_PX = 10
}
