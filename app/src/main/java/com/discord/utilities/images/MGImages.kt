package com.discord.utilities.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.views.VitoImageView
import com.facebook.drawee.controller.ControllerListener
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.generic.RoundingParams
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.source.ImageSourceProvider
import com.facebook.fresco.vito.view.VitoView
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.image.ImageInfo
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.imagepipeline.request.ImageRequestBuilder
import java.io.IOException

/**
     * Thin, opinionated wrapper over Fresco Vito / ImagePipeline used by every first-party view.
 *
 * Reference: `com/discord/utilities/images/MGImages.java`.
 *
 * Image picking and crop activity results are owned by `com.discord.media_picker`; this object
 * handles image display and conversion only.
 */
object MGImages {

    private const val TAG = "MGImages"

    private val imageEncoder = ImageEncoder()

    /**
     * Guards redundant image requests. RecyclerView rebinds the same view with the same URL
     * constantly; re-issuing the request for an unchanged value causes a visible flicker
     * and wasted pipeline work.
     */
    interface ChangeDetector {
        /** @return `true` when [value] differs from the last value tracked for [key]. */
        fun track(key: Any, value: Any?): Boolean
    }

    /** Default detector: every call is treated as a change. */
    object AlwaysUpdateChangeDetector : ChangeDetector {
        override fun track(key: Any, value: Any?): Boolean = true
    }

    /**
     * Suppresses repeated requests for an unchanged (key, value) pair. One instance is normally
     * owned by an adapter so its state spans view rebinds.
     */
    class DistinctChangeDetector : ChangeDetector {
        private val dataMap = HashMap<Any, Any?>()

        override fun track(key: Any, value: Any?): Boolean {
            if (dataMap.containsKey(key) && dataMap[key] == value) return false
            dataMap[key] = value
            return true
        }

        fun clear(key: Any) {
            dataMap.remove(key)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Requests
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the pipeline request for [url].
     *
     * Discord routes practically everything through Fresco's small-image disk cache; only animated
     * content requested with [useSmallCache] disabled goes to the default cache, because GIF frames
     * would otherwise evict the avatar/icon working set.
     */
    @JvmStatic
    fun getImageRequest(
        url: String,
        width: Int,
        height: Int,
        useSmallCache: Boolean,
    ): ImageRequestBuilder {
        val builder = ImageRequestBuilder.newBuilderWithSource(Uri.parse(url))
            .setLowestPermittedRequestLevel(ImageRequest.RequestLevel.FULL_FETCH)
        builder.setCacheChoice(
            if (!useSmallCache && url.contains(IconUtils.ANIMATED_IMAGE_EXTENSION)) {
                ImageRequest.CacheChoice.DEFAULT
            } else {
                ImageRequest.CacheChoice.SMALL
            },
        )
        if (width > 0 && height > 0) {
            builder.setResizeOptions(ResizeOptions(width, height))
        }
        return builder
    }

    // ---------------------------------------------------------------------------------------
    // Binding
    // ---------------------------------------------------------------------------------------

    @JvmStatic
    @JvmOverloads
    fun setImage(
        view: ImageView,
        url: String?,
        width: Int = 0,
        height: Int = 0,
        useSmallCache: Boolean = false,
        transform: ((ImageRequestBuilder) -> Unit)? = null,
        changeDetector: ChangeDetector = AlwaysUpdateChangeDetector,
        autoPlayAnimations: Boolean = true,
    ) {
        val urls = if (url.isNullOrBlank()) emptyList() else listOf(url)
        setImage(view, urls, width, height, useSmallCache, transform, changeDetector, null, autoPlayAnimations)
    }

    /**
     * Binds the first request in [urls] that resolves, matching Fresco's "first available" builder.
     *
     * Nothing happens when [changeDetector] reports the URL list as unchanged; an empty list clears
     * the view.
     */
    @JvmStatic
    @JvmOverloads
    fun setImage(
        view: ImageView,
        urls: List<String>,
        width: Int = 0,
        height: Int = 0,
        useSmallCache: Boolean = false,
        transform: ((ImageRequestBuilder) -> Unit)? = null,
        changeDetector: ChangeDetector = AlwaysUpdateChangeDetector,
        controllerListener: ControllerListener<ImageInfo>? = null,
        autoPlayAnimations: Boolean = true,
    ) {
        if (!changeDetector.track(view, urls to autoPlayAnimations)) return
        if (view is VitoImageView) {
            view.show(urls, width, height, autoPlayAnimations, transform, controllerListener, useSmallCache)
            return
        }
        if (urls.isEmpty()) {
            VitoView.release(view)
            return
        }
        val options = ImageOptions.create()
            .autoPlay(autoPlayAnimations)
            .apply {
                if (width > 0 && height > 0) resize(ResizeOptions(width, height))
            }
            .build()
        VitoView.show(ImageSourceProvider.firstAvailable(*urls.map(ImageSourceProvider::forUri).toTypedArray()), options, view)
    }

    @JvmOverloads
    fun setImage(
        view: ImageView,
        uri: Uri,
        changeDetector: ChangeDetector = AlwaysUpdateChangeDetector,
    ) {
        if (uri.scheme == "android.resource" && uri.host == view.context.packageName) {
            setImage(view, uri.lastPathSegment?.toIntOrNull() ?: 0, changeDetector)
            return
        }
        setImage(view, uri.toString(), changeDetector = changeDetector)
    }

    @JvmOverloads
    fun setImage(
        view: ImageView,
        @DrawableRes resourceId: Int,
        changeDetector: ChangeDetector = AlwaysUpdateChangeDetector,
    ) {
        if (!changeDetector.track(view, resourceId)) return
        view.setImageResource(resourceId)
    }

    @JvmOverloads
    fun setImage(
        view: ImageView,
        @DrawableRes resourceId: Int,
        scaleType: ScalingUtils.ScaleType,
        changeDetector: ChangeDetector = AlwaysUpdateChangeDetector,
    ) {
        if (!changeDetector.track(view, resourceId)) return
        if (view is VitoImageView) view.hierarchy.setActualImageScaleType(scaleType)
        view.setImageResource(resourceId)
    }

    @JvmOverloads
    fun setImage(
        view: ImageView,
        drawable: Drawable,
        changeDetector: ChangeDetector = AlwaysUpdateChangeDetector,
    ) {
        if (!changeDetector.track(view, drawable)) return
        view.setImageDrawable(drawable)
    }

    fun cancelImageRequests(view: ImageView) {
        if (view is VitoImageView) view.release() else VitoView.release(view)
    }

    // ---------------------------------------------------------------------------------------
    // Hierarchy configuration
    // ---------------------------------------------------------------------------------------

    @JvmStatic
    fun setScaleType(view: ImageView, scaleType: ScalingUtils.ScaleType) {
        if (view is VitoImageView) view.hierarchy.setActualImageScaleType(scaleType)
    }

    /**
     * @param circle when set, the image is rounded as a circle drawn with the overlay-color method
     *   rather than by rewriting the bitmap, which is what Discord uses for avatars.
     */
    @JvmStatic
    @JvmOverloads
    fun setRoundingParams(
        view: ImageView,
        cornerRadius: Float,
        circle: Boolean,
        @ColorInt overlayColor: Int? = null,
        @ColorInt borderColor: Int? = null,
        borderWidth: Float? = null,
    ) {
        val roundingParams = if (circle) {
            RoundingParams.asCircle().apply {
                if (overlayColor != null) setRoundingMethod(RoundingParams.RoundingMethod.OVERLAY_COLOR)
            }
        } else {
            RoundingParams.fromCornersRadius(cornerRadius)
        }
        // Android P mis-renders rounded bitmaps without bitmap filtering.
        if (Build.VERSION.SDK_INT == 28) {
            roundingParams.setPaintFilterBitmap(true)
        }
        overlayColor?.let { roundingParams.setOverlayColor(it) }
        borderColor?.let { roundingParams.setBorderColor(it) }
        borderWidth?.let { roundingParams.setBorderWidth(it) }
        if (view is VitoImageView) view.hierarchy.roundingParams = roundingParams
    }

    // ---------------------------------------------------------------------------------------
    // Encoding helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Reads [uri] through the content resolver and hands a `data:` URL to [resultCallback].
     *
     * Read failures are logged and returned through [onError] so the owning screen can show its
     * context-specific error.
     */
    fun requestDataUrl(
        context: Context?,
        uri: Uri,
        mimeType: String,
        resultCallback: ((String) -> Unit)?,
        onError: (Exception) -> Unit = {},
    ) {
        try {
            val resolvedContext = context ?: throw IllegalStateException("Image request has no context")
            val stream = resolvedContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open selected image")
            stream.use {
                resultCallback?.invoke(imageEncoder.getDataUrl(mimeType, stream))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Unable to convert image to a data url", e)
            onError(e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Unable to convert image to a data url", e)
            onError(e)
        } catch (e: NullPointerException) {
            Log.w(TAG, "Unable to convert image to a data url", e)
            onError(e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read selected image", e)
            onError(e)
        }
    }

    /**
     * Draws [src] centered inside a new bitmap of [outerWidth] x [outerHeight].
     *
     * @param fillMargins inset applied to the [fillColor] backdrop, leaving a transparent frame.
     */
    @JvmOverloads
    fun centerBitmapInTransparentBitmap(
        src: Bitmap,
        innerHeight: Int,
        innerWidth: Int,
        outerHeight: Int,
        outerWidth: Int,
        fillMargins: Int = 0,
        @ColorInt fillColor: Int = 0,
    ): Bitmap {
        require(outerWidth > innerWidth && outerHeight > innerHeight) {
            "Cannot fit bitmap of size $innerWidth x $innerHeight inside " +
                "bitmap of size $outerWidth x $outerHeight"
        }
        val dest =
            Bitmap.createBitmap(outerWidth, outerHeight, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val left = (outerWidth - innerWidth) / 2f
        val top = (outerHeight - innerHeight) / 2f
        val target = RectF(left, top, innerWidth + left, innerHeight + top)
        if (fillColor != 0) {
            val save = canvas.save()
            try {
                canvas.clipRect(
                    fillMargins,
                    fillMargins,
                    outerWidth - fillMargins,
                    outerHeight - fillMargins,
                )
                canvas.drawColor(fillColor)
            } finally {
                canvas.restoreToCount(save)
            }
        }
        canvas.drawBitmap(src, null as Rect?, target, null as Paint?)
        return dest
    }
}
