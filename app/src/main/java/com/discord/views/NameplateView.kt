package com.discord.views

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
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
import com.discord.utilities.images.CollectibleAssets
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
import okhttp3.Request
import kotlin.coroutines.coroutineContext

class NameplateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    enum class Placement { MEMBER_LIST, CHANNEL }

    internal data class AnimationRequest(val key: String, val generation: Int)

    private val image = SimpleDraweeView(context)
    private val animatedImage = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.MATRIX
        visibility = INVISIBLE
    }
    private val changeDetector = MGImages.DistinctChangeDetector()
    private var nameplate: Nameplate? = null
    private var placement = Placement.MEMBER_LIST
    private var hovered = false
    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var animationJob: Job? = null
    @Volatile
    private var animationCall: Call? = null
    private var animatedDrawable: ApngDrawable? = null
    private var animationRequest: AnimationRequest? = null
    private var animationAttempted = false
    private var animationGeneration = 0
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
    }

    fun bind(nameplate: Nameplate?, placement: Placement, selected: Boolean) {
        clearChannelAnimation()
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
        // 0dp during measurement, then laid out over the row's selection pill, which the row's
        // horizontal padding matches.
        layout(parent.paddingLeft, 0, parent.width - parent.paddingRight, parent.height)
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
        image.alpha = imageAlpha()
        animatedImage.alpha = image.alpha
        updateChannelAnimation(plate, reducedMotion)

        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        background = plate.backgroundColorHex(dark)?.let { hex ->
            runCatching { Color.parseColor(hex) }.getOrNull()?.let { color ->
                NameplateGradientDrawable(color).apply {
                    alpha = (gradientAlpha() * 255f).toInt()
                }
            }
        }
        image.invalidate()
    }

    /**
     * Pressing brightens a channel plate and dims a member-list one; the reference calls the latter
     * `invertPressOpacity`.
     */
    private fun invertPressOpacity() = placement == Placement.MEMBER_LIST

    private fun gradientAlpha(): Float = when {
        isSelected -> 0.8f
        hovered -> if (invertPressOpacity()) 0.3f else 0.6f
        else -> 0.4f
    }

    private fun imageAlpha(): Float = when {
        isSelected -> 0.8f
        hovered -> if (invertPressOpacity()) 0.4f else 0.5f
        else -> 0.6f
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
                    val call = CollectibleAssets.client.newCall(Request.Builder().url(request.key).build())
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

    /** Reference: a plain horizontal ramp from fully transparent to 30% of the palette colour. */
    private class NameplateGradientDrawable(private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            paint.shader = LinearGradient(
                bounds.left.toFloat(),
                0f,
                bounds.right.toFloat(),
                0f,
                color.withAlpha(0x00),
                color.withAlpha(0x4D),
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

    companion object {
        /** Trailing space a row reserves for the artwork. */
        const val ART_GUTTER_DP = 40
    }
}
