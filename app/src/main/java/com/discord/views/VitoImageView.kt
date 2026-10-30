package com.discord.views

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import com.discord.utilities.icon.IconUtils
import com.facebook.drawee.controller.ControllerListener
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.generic.GenericDraweeHierarchyInflater
import com.facebook.drawee.generic.RoundingParams
import com.facebook.drawee.R as DraweeR
import com.facebook.fresco.vito.listener.BaseImageListener
import com.facebook.fresco.vito.listener.ImageListener
import com.facebook.fresco.vito.options.BorderOptions
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.RoundingOptions
import com.facebook.fresco.vito.source.ImageSource
import com.facebook.fresco.vito.source.ImageSourceProvider
import com.facebook.fresco.vito.view.VitoView
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.image.ImageInfo
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.imagepipeline.request.ImageRequestBuilder

open class VitoImageView : AppCompatImageView {
    var imageOptions: ImageOptions = ImageOptions.create().build()
        private set
    var aspectRatio: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    val hierarchy = Hierarchy()
    private var lastSource: ImageSource? = null

    /**
     * The URI [setImageURI] last put on screen. Rows rebind on every store update (a voice row on
     * each speaking change), and re-showing the same image restarts the request, so the picture
     * visibly reloads for no reason.
     */
    private var shownUri: Uri? = null

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        applyAttrs(attrs)
    }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        applyAttrs(attrs)
    }

    override fun setImageURI(uri: Uri?) {
        if (uri == null) {
            release()
            return
        }
        if (uri == shownUri && lastSource != null) return
        val resId = uri.rawResourceId()
        if (resId != 0) {
            setActualImageResource(resId)
            return
        }
        show(ImageSourceProvider.forUri(uri), imageOptions)
        shownUri = uri
    }

    fun setImageURI(uriString: String?) {
        if (uriString.isNullOrBlank()) release() else setImageURI(Uri.parse(uriString))
    }

    fun setActualImageResource(resId: Int) {
        setImageResource(resId)
        shownUri = null
        lastSource = null
    }

    fun release() {
        shownUri = null
        lastSource = null
        VitoView.release(this)
    }

    fun show(
        urls: List<String>,
        width: Int = 0,
        height: Int = 0,
        autoPlay: Boolean = true,
        transform: ((ImageRequestBuilder) -> Unit)? = null,
        controllerListener: ControllerListener<ImageInfo>? = null,
        useSmallCache: Boolean = true,
    ) {
        if (urls.isEmpty()) {
            release()
            return
        }
        val resId = urls.singleOrNull()?.let { Uri.parse(it).rawResourceId() } ?: 0
        if (resId != 0) {
            setActualImageResource(resId)
            return
        }
        val builder = imageOptions.extend().autoPlay(autoPlay).releaseOnWindowDetached(false)
        builder.cacheChoice(
            if (!useSmallCache && urls.any { it.contains(IconUtils.ANIMATED_IMAGE_EXTENSION) }) {
                ImageRequest.CacheChoice.DEFAULT
            } else {
                ImageRequest.CacheChoice.SMALL
            },
        )
        if (width > 0 && height > 0) builder.resize(ResizeOptions(width, height))
        if (transform != null) {
            val request = ImageRequestBuilder.newBuilderWithSource(Uri.parse(urls.first()))
            transform(request)
            request.postprocessor?.let { builder.postprocess(it) }
        }
        val source = if (urls.size == 1) {
            ImageSourceProvider.forUri(urls[0])
        } else {
            ImageSourceProvider.firstAvailable(*urls.map(ImageSourceProvider::forUri).toTypedArray())
        }
        show(source, builder.build(), controllerListener?.let(::ControllerAdapter))
    }

    fun show(source: ImageSource, options: ImageOptions = imageOptions, listener: ImageListener? = null) {
        shownUri = null
        lastSource = source
        imageOptions = options
        VitoView.show(source, options, null, listener, this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (aspectRatio <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        if (widthMode != MeasureSpec.UNSPECIFIED) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec((width / aspectRatio).toInt(), MeasureSpec.EXACTLY),
            )
        } else if (heightMode != MeasureSpec.UNSPECIFIED) {
            val height = MeasureSpec.getSize(heightMeasureSpec)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec((height * aspectRatio).toInt(), MeasureSpec.EXACTLY),
                heightMeasureSpec,
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun applyAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        val typedArray = context.obtainStyledAttributes(attrs, intArrayOf(DraweeR.attr.viewAspectRatio))
        try {
            aspectRatio = typedArray.getFloat(0, 0f)
        } finally {
            typedArray.recycle()
        }
        imageOptions = inflateOptions(context, attrs).build()
    }

    private fun updateOptions(block: ImageOptions.Builder.() -> Unit) {
        imageOptions = imageOptions.extend().apply(block).build()
        lastSource?.let { show(it, imageOptions) }
    }

    private fun Uri.rawResourceId(): Int =
        if (scheme == "res") lastPathSegment?.toIntOrNull() ?: 0 else 0

    inner class Hierarchy {
        var roundingParams: RoundingParams? = null
            set(value) {
                field = value
                updateOptions {
                    round(value?.toRoundingOptions())
                    value?.toBorderOptions()?.let { borders(it) }
                }
            }
        var fadeDuration: Int
            get() = imageOptions.fadeDurationMs
            set(value) { updateOptions { fadeDurationMs(value) } }

        fun setActualImageScaleType(scaleType: ScalingUtils.ScaleType) {
            updateOptions { scale(scaleType) }
        }

        fun setPlaceholderImage(drawable: Drawable?) {
            updateOptions { placeholder(drawable) }
        }

        fun setPlaceholderImage(resId: Int) {
            updateOptions { placeholderRes(resId) }
        }

        fun setPlaceholderImage(drawable: Drawable?, scaleType: ScalingUtils.ScaleType) {
            updateOptions { placeholder(drawable, scaleType) }
        }
    }

    private class ControllerAdapter(
        private val listener: ControllerListener<ImageInfo>,
    ) : BaseImageListener() {
        override fun onSubmit(id: Long, callerContext: Any?) {
            listener.onSubmit(id.toString(), callerContext)
        }

        override fun onFinalImageSet(id: Long, imageOrigin: Int, imageInfo: ImageInfo?, drawable: Drawable?) {
            listener.onFinalImageSet(id.toString(), imageInfo, drawable as? Animatable)
        }

        override fun onFailure(id: Long, error: Drawable?, throwable: Throwable?) {
            listener.onFailure(id.toString(), throwable)
        }

        override fun onRelease(id: Long) {
            listener.onRelease(id.toString())
        }
    }

    companion object {
        // ponytail: skip official VitoViewInflater — it throws on backgroundImage / roundWithOverlayColor
        private fun inflateOptions(context: Context, attrs: AttributeSet): ImageOptions.Builder {
            val builder = ImageOptions.create()
            val typed = context.obtainStyledAttributes(attrs, DraweeR.styleable.GenericDraweeHierarchy)
            try {
                for (i in 0 until typed.indexCount) {
                    when (val attr = typed.getIndex(i)) {
                        DraweeR.styleable.GenericDraweeHierarchy_actualImageScaleType ->
                            builder.scale(GenericDraweeHierarchyInflater.getScaleTypeFromXml(typed, attr))
                        DraweeR.styleable.GenericDraweeHierarchy_placeholderImage ->
                            builder.placeholder(GenericDraweeHierarchyInflater.getDrawable(context, typed, attr))
                        DraweeR.styleable.GenericDraweeHierarchy_placeholderImageScaleType ->
                            builder.placeholderScaleType(GenericDraweeHierarchyInflater.getScaleTypeFromXml(typed, attr))
                        DraweeR.styleable.GenericDraweeHierarchy_fadeDuration ->
                            builder.fadeDurationMs(typed.getInt(attr, 0))
                        DraweeR.styleable.GenericDraweeHierarchy_failureImage ->
                            builder.errorDrawable(GenericDraweeHierarchyInflater.getDrawable(context, typed, attr))
                        DraweeR.styleable.GenericDraweeHierarchy_overlayImage ->
                            builder.overlay(GenericDraweeHierarchyInflater.getDrawable(context, typed, attr))
                        DraweeR.styleable.GenericDraweeHierarchy_backgroundImage ->
                            builder.background(GenericDraweeHierarchyInflater.getDrawable(context, typed, attr))
                        DraweeR.styleable.GenericDraweeHierarchy_roundAsCircle ->
                            if (typed.getBoolean(attr, false)) {
                                builder.round(RoundingOptions.asCircle(antiAliasing = true, forceRoundAtDecode = true))
                            }
                        DraweeR.styleable.GenericDraweeHierarchy_roundedCornerRadius -> {
                            val radius = typed.getDimensionPixelSize(attr, 0).toFloat()
                            if (radius > 0f) builder.round(RoundingOptions.forCornerRadiusPx(radius))
                        }
                        DraweeR.styleable.GenericDraweeHierarchy_roundingBorderWidth -> {
                            val width = typed.getDimensionPixelSize(attr, 0).toFloat()
                            if (width != 0f) {
                                builder.borders(
                                    BorderOptions.create(
                                        typed.getColor(DraweeR.styleable.GenericDraweeHierarchy_roundingBorderColor, 0),
                                        width,
                                        typed.getDimensionPixelSize(
                                            DraweeR.styleable.GenericDraweeHierarchy_roundingBorderPadding,
                                            0,
                                        ).toFloat(),
                                    ),
                                )
                            }
                        }
                    }
                }
            } finally {
                typed.recycle()
            }
            return builder
        }

        private fun RoundingParams.toRoundingOptions(): RoundingOptions =
            if (roundAsCircle) {
                RoundingOptions.asCircle(antiAliasing = true, forceRoundAtDecode = true)
            } else {
                cornersRadii?.let { RoundingOptions.forCornerRadii(it) }
                    ?: RoundingOptions.forCornerRadiusPx(0f)
            }

        private fun RoundingParams.toBorderOptions(): BorderOptions? =
            borderWidth.takeIf { it > 0f }?.let { BorderOptions.create(borderColor, it) }
    }
}
