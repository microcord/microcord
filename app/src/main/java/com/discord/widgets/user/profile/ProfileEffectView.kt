package com.discord.widgets.user.profile

import android.content.Context
import android.graphics.Matrix
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.discord.api.user.UserProfile
import com.discord.utilities.images.CollectibleAssets
import com.discord.utilities.images.MGImages
import com.discord.views.VitoImageView
import com.linecorp.apng.ApngDrawable
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import kotlin.random.Random

class ProfileEffectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val handler = Handler(Looper.getMainLooper())
    private var scope = newScope()
    private val calls = CopyOnWriteArrayList<Call>()
    private val drawables = mutableListOf<ApngDrawable>()
    private var generation = 0
    private val layerPositions = mutableListOf<Pair<ImageView, UserProfile.ProfileEffectLayer>>()
    private var boundEffect: UserProfile.ProfileEffect? = null
    private var boundReducedMotion: Boolean? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(CORNER_RADIUS_DP).toFloat())
            }
        }
    }

    fun bind(effect: UserProfile.ProfileEffect?, reducedMotion: Boolean) {
        if (effect == boundEffect && reducedMotion == boundReducedMotion) return
        clear()
        boundEffect = effect
        boundReducedMotion = reducedMotion
        val item = effect?.product?.items?.firstOrNull {
            it.type == UserProfile.ProfileEffectItem.TYPE
        } ?: return
        val currentGeneration = generation
        if (reducedMotion) {
            val source = item.reducedMotionSrc ?: item.staticFrameSrc ?: return
            visibility = VISIBLE
            showStill(source)
            return
        }
        val layers = item.resolvedLayers()
        if (layers.isEmpty()) {
            item.staticFrameSrc?.let {
                visibility = VISIBLE
                showStill(it)
            }
            return
        }
        visibility = VISIBLE
        showAnimated(layers, currentGeneration)
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }

    internal fun playDecodedLayers(
        layers: List<Pair<UserProfile.ProfileEffectLayer, ApngDrawable>>,
        introDelay: Long = INTRO_DELAY_MS,
    ) {
        layers.sortedBy { it.first.zIndex }.forEach { (layer, drawable) ->
            drawables += drawable
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.MATRIX
                visibility = INVISIBLE
                setImageDrawable(drawable)
            }
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            layerPositions += image to layer
            positionLayer(image, layer)
            scheduleLayer(image, drawable, layer, introDelay)
        }
    }

    internal fun clear() {
        generation++
        boundEffect = null
        boundReducedMotion = null
        handler.removeCallbacksAndMessages(null)
        calls.forEach(Call::cancel)
        calls.clear()
        scope.cancel()
        scope = newScope()
        children().filterIsInstance<VitoImageView>().forEach(MGImages::cancelImageRequests)
        children().filterIsInstance<ImageView>().filterNot { it is VitoImageView }
            .forEach { it.setImageDrawable(null) }
        drawables.forEach {
            it.stop()
            it.clearAnimationCallbacks()
            it.recycle()
        }
        drawables.clear()
        layerPositions.clear()
        removeAllViews()
        visibility = GONE
    }

    private fun showAnimated(
        layers: List<UserProfile.ProfileEffectLayer>,
        currentGeneration: Int,
    ) {
        if (layers.isEmpty()) return hideIfCurrent(currentGeneration)
        val startedAt = SystemClock.uptimeMillis()
        var remaining = layers.size
        var shown = false
        layers.forEach { layer ->
            scope.launch {
                var drawable: ApngDrawable? = null
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        val call = CollectibleAssets.client.newCall(Request.Builder().url(layer.src).build()).also(calls::add)
                        try {
                            call.execute().use { response ->
                                check(response.isSuccessful)
                                checkNotNull(response.body).bytes()
                            }
                        } finally {
                            calls.remove(call)
                        }
                    }
                    val playAt = startedAt + INTRO_DELAY_MS + layer.start
                    val decodeAt = if (layer.start == 0L) startedAt else playAt
                    delay((decodeAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
                    drawable = withContext(Dispatchers.IO) {
                        ApngDrawable.decode(ByteArrayInputStream(bytes))
                    }
                    delay((playAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
                    if (currentGeneration != generation) {
                        return@launch
                    }
                    val decoded = drawable ?: return@launch
                    playDecodedLayers(listOf(layer.copy(start = 0) to decoded), introDelay = 0)
                    drawable = null
                    shown = true
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                } finally {
                    drawable?.recycle()
                    remaining--
                    if (remaining == 0 && !shown) hideIfCurrent(currentGeneration)
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Layer offsets are in the desktop profile's coordinate space, so they can only be scaled
        // once this view has a width; binding happens before the first layout pass.
        layerPositions.forEach { (image, layer) -> positionLayer(image, layer) }
    }

    /**
     * Each layer is authored at its own size and offset in the desktop profile's 304pt-wide space,
     * so it is drawn through a matrix rather than stretched to the view: fitting every layer to the
     * full bounds would scale them all to the same rectangle and lose the composition.
     */
    private fun positionLayer(image: ImageView, layer: UserProfile.ProfileEffectLayer) {
        val scale = width.takeIf { it > 0 }?.toFloat()?.div(DESKTOP_PROFILE_WIDTH) ?: return
        image.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((layer.position?.x ?: 0) * scale, (layer.position?.y ?: 0) * scale)
        }
        image.invalidate()
    }

    private fun showStill(url: String) {
        val image = VitoImageView(context).apply { scaleType = ImageView.ScaleType.FIT_START }
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        MGImages.setImage(image, url, useSmallCache = false, autoPlayAnimations = false)
    }

    private fun scheduleLayer(
        image: ImageView,
        drawable: ApngDrawable,
        layer: UserProfile.ProfileEffectLayer,
        introDelay: Long,
    ) {
        val duration = layer.duration.takeIf { it > 0 } ?: drawable.durationMillis.toLong()
        handler.postDelayed({
            drawable.stop()
            drawable.seekTo(0)
            drawable.loopCount = 1
            image.visibility = VISIBLE
            drawable.start()
            handler.postDelayed({
                drawable.stop()
                image.visibility = INVISIBLE
                drawables.remove(drawable)
                removeView(image)
                image.setImageDrawable(null)
                drawable.clearAnimationCallbacks()
                drawable.recycle()
            }, duration)
        }, introDelay + layer.start)
    }

    private fun UserProfile.ProfileEffectItem.resolvedLayers(): List<UserProfile.ProfileEffectLayer> {
        val sourceCount = effects
            .map { it.randomizedSources.orEmpty().size }
            .filter { it > 0 }
            .minOrNull()
            ?: 0
        if (sourceCount == 0) return effects
        val index = Random.nextInt(sourceCount)
        return effects.map { layer ->
            layer.randomizedSources.orEmpty().getOrNull(index)?.let { layer.copy(src = it.src) } ?: layer
        }
    }

    private fun hideIfCurrent(currentGeneration: Int) {
        if (currentGeneration == generation) visibility = GONE
    }

    private fun children() = (0 until childCount).asSequence().map(::getChildAt)
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val INTRO_DELAY_MS = 500L
        const val DESKTOP_PROFILE_WIDTH = 304f
        const val CORNER_RADIUS_DP = 8
    }

}
