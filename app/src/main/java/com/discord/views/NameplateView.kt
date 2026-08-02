package com.discord.views

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.discord.api.user.Nameplate
import com.discord.stores.StoreStream
import com.discord.utilities.images.MGImages
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.view.SimpleDraweeView
import com.linecorp.apng.ApngDrawable
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

class NameplateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    enum class Placement { MEMBER_LIST, CHANNEL }

    internal data class AnimationRequest(val key: String, val generation: Int)

    private val image = MaskedDraweeView(context)
    private val animatedImage = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.MATRIX
        visibility = INVISIBLE
    }
    private val changeDetector = MGImages.DistinctChangeDetector()
    private var nameplate: Nameplate? = null
    private var placement = Placement.MEMBER_LIST
    private var hovered = false
    private var contentView: View? = null
    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var animationJob: Job? = null
    @Volatile
    private var animationCall: Call? = null
    private var animatedDrawable: ApngDrawable? = null
    private var animationRequest: AnimationRequest? = null
    private var animationAttempted = false
    private var animationGeneration = 0
    private val contentLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> image.invalidate() }
    private val parentLayoutListener = View.OnLayoutChangeListener { parent, _, _, _, _, _, _, _, _ ->
        layoutToParent(parent)
    }
    private val channelLayoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
        val parent = view.parent as? View
        if (parent != null && placement == Placement.CHANNEL &&
            (left != 0 || top != 0 || right != parent.width || bottom != parent.height)
        ) {
            layoutToParent(parent)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
        isClickable = false
        isFocusable = false
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 4f * resources.displayMetrics.density)
            }
        }
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(animatedImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addOnLayoutChangeListener(channelLayoutListener)
        MGImages.setScaleType(image, ArtworkScaleType)
        image.maskEdge = ::maskEdge
    }

    fun bind(nameplate: Nameplate?, placement: Placement, selected: Boolean, contentView: View) {
        clearChannelAnimation()
        this.contentView?.removeOnLayoutChangeListener(contentLayoutListener)
        this.contentView = contentView.also { it.addOnLayoutChangeListener(contentLayoutListener) }
        this.nameplate = nameplate?.takeIf { it.isActive() && it.staticImageUrl() != null }
        this.placement = placement
        hovered = false
        isSelected = selected
        installParentLayoutListener()
        updateVisuals()
    }

    fun setNameplateHovered(hovered: Boolean) {
        if (this.hovered == hovered) return
        this.hovered = hovered
        updateVisuals()
    }

    override fun onDetachedFromWindow() {
        clearChannelAnimation()
        contentView?.removeOnLayoutChangeListener(contentLayoutListener)
        (parent as? View)?.removeOnLayoutChangeListener(parentLayoutListener)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        installParentLayoutListener()
        updateVisuals()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        image.layout(0, 0, width, height)
        animatedImage.layout(0, 0, width, height)
        updateAnimatedImageMatrix()
    }

    private fun installParentLayoutListener() {
        (parent as? View)?.let {
            it.removeOnLayoutChangeListener(parentLayoutListener)
            it.addOnLayoutChangeListener(parentLayoutListener)
            if (it.isLaidOut) layoutToParent(it)
        }
    }

    private fun layoutToParent(parent: View) {
        if (placement != Placement.CHANNEL) return
        // Channel nameplates are 0dp during measurement and overlay the full final row.
        layout(0, 0, parent.width, parent.height)
    }

    private fun updateVisuals() {
        val plate = nameplate
        if (plate == null) {
            clearChannelAnimation()
            visibility = GONE
            background = null
            MGImages.setImage(image, emptyList(), changeDetector = changeDetector)
            return
        }

        visibility = VISIBLE
        val reducedMotion = runCatching { StoreStream.getAccessibility().isReducedMotionEnabled() }
            .getOrDefault(false)
        val animateMemberList = placement == Placement.MEMBER_LIST && !reducedMotion && (hovered || isSelected)
        MGImages.setImage(
            image,
            plate.imageUrls(animateMemberList),
            changeDetector = changeDetector,
            autoPlayAnimations = animateMemberList,
        )
        image.alpha = when {
            isSelected -> 0.9f
            placement == Placement.CHANNEL && hovered -> 0.4f
            else -> 0.6f
        }
        animatedImage.alpha = image.alpha
        updateChannelAnimation(plate, reducedMotion)

        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        background = plate.backgroundColorHex(dark)?.let { hex ->
            runCatching { Color.parseColor(hex) }.getOrNull()?.let { color ->
                NameplateGradientDrawable(
                    color,
                    when {
                        isSelected -> 0x80
                        hovered && placement == Placement.MEMBER_LIST -> 0x4D
                        else -> 0x33
                    },
                )
            }
        }
        image.invalidate()
    }

    internal fun currentAnimationRequest(): AnimationRequest? = animationRequest

    internal fun bindDecodedAnimation(drawable: ApngDrawable, request: AnimationRequest): Boolean {
        if (request != animationRequest || !isSelected || placement != Placement.CHANNEL) {
            recycle(drawable)
            return false
        }
        animatedDrawable?.let(::recycle)
        animatedDrawable = drawable
        drawable.loopCount = ApngDrawable.LOOP_FOREVER
        animatedImage.setImageDrawable(drawable)
        animatedImage.visibility = VISIBLE
        updateAnimatedImageMatrix()
        drawable.start()
        return true
    }

    private fun updateChannelAnimation(plate: Nameplate, reducedMotion: Boolean) {
        val animatedUrl = plate.imageUrls(animate = true).firstOrNull()
        val desiredKey = animatedUrl?.takeIf {
            placement == Placement.CHANNEL && isSelected && !reducedMotion && it != plate.staticImageUrl()
        }
        if (desiredKey == null) {
            if (animationRequest != null) clearChannelAnimation()
            return
        }
        if (animationRequest?.key != desiredKey) {
            clearChannelAnimation()
            animationRequest = AnimationRequest(desiredKey, animationGeneration)
        }
        if (!isAttachedToWindow || animationAttempted) return
        animationAttempted = true
        val request = animationRequest ?: return
        animationJob = animationScope.launch {
            var decoded: ApngDrawable? = null
            try {
                decoded = withContext(Dispatchers.IO) {
                    val call = client.newCall(Request.Builder().url(request.key).build())
                    animationCall = call
                    val cancellation = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
                    try {
                        call.execute().use { response ->
                            check(response.isSuccessful)
                            ApngDrawable.decode(ByteArrayInputStream(checkNotNull(response.body).bytes()))
                        }
                    } finally {
                        cancellation?.dispose()
                        if (animationCall === call) animationCall = null
                    }
                }
                val drawable = decoded ?: return@launch
                if (bindDecodedAnimation(drawable, request)) decoded = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                decoded?.recycle()
                if (animationRequest == request) animationJob = null
            }
        }
    }

    private fun clearChannelAnimation() {
        animationGeneration++
        animationRequest = null
        animationAttempted = false
        animationCall?.cancel()
        animationCall = null
        animationJob?.cancel()
        animationJob = null
        animatedImage.visibility = INVISIBLE
        animatedImage.setImageDrawable(null)
        animatedDrawable?.let(::recycle)
        animatedDrawable = null
    }

    private fun recycle(drawable: ApngDrawable) {
        drawable.stop()
        drawable.clearAnimationCallbacks()
        drawable.recycle()
    }

    private fun updateAnimatedImageMatrix() {
        val drawable = animatedDrawable ?: return
        val childWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return
        val childHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: return
        if (width == 0 || height == 0) return
        val scale = height.toFloat() / childHeight
        animatedImage.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(width - childWidth * scale, 0f)
        }
    }

    internal fun maskEdge(): Float? {
        if (placement == Placement.CHANNEL) return null
        val content = contentView ?: return null
        if (!content.isLaidOut || !isLaidOut) return null
        val contentLocation = IntArray(2).also(content::getLocationInWindow)
        val ownLocation = IntArray(2).also(::getLocationInWindow)
        return contentLocation[0] + content.width - ownLocation[0] + 10f * resources.displayMetrics.density
    }

    private object ArtworkScaleType : ScalingUtils.ScaleType {
        override fun getTransform(
            outTransform: Matrix,
            parentRect: Rect,
            childWidth: Int,
            childHeight: Int,
            focusX: Float,
            focusY: Float,
        ): Matrix {
            val scale = parentRect.height().toFloat() / childHeight
            outTransform.setScale(scale, scale)
            outTransform.postTranslate(
                parentRect.right - childWidth * scale,
                parentRect.top + (parentRect.height() - childHeight * scale) / 2f,
            )
            return outTransform
        }
    }

    private class MaskedDraweeView(context: Context) : SimpleDraweeView(context) {
        var maskEdge: () -> Float? = { null }
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        override fun onDraw(canvas: Canvas) {
            val edge = maskEdge() ?: return super.onDraw(canvas)
            val fadeEnd = edge + 50f * resources.displayMetrics.density
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            super.onDraw(canvas)
            maskPaint.shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                0f,
                intArrayOf(0x4DFFFFFF, 0x4DFFFFFF, Color.WHITE),
                floatArrayOf(0f, (edge / width).coerceIn(0f, 1f), (fadeEnd / width).coerceIn(0f, 1f)),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
            canvas.restoreToCount(layer)
        }
    }

    private class NameplateGradientDrawable(
        private val color: Int,
        private val endAlpha: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            paint.shader = LinearGradient(
                bounds.left.toFloat(),
                0f,
                bounds.right.toFloat(),
                0f,
                intArrayOf(Color.TRANSPARENT, color.withAlpha(0x14), color.withAlpha(0x14), color.withAlpha(endAlpha)),
                floatArrayOf(0f, 0.2f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) = canvas.drawRect(bounds, paint)
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private fun Int.withAlpha(alpha: Int) = Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
    }

    private companion object {
        val client = OkHttpClient()
    }
}
