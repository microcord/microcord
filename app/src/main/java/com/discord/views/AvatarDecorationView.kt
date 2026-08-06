package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.discord.R
import com.discord.api.user.AvatarDecorationData
import com.discord.stores.StoreStream
import com.discord.utilities.images.CollectibleAssets
import com.discord.utilities.images.MGImages
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

/**
 * The collectible frame drawn around an avatar.
 *
 * Declared with `0dp` bounds and an `avd_target` sibling: the frame is [DECORATION_TO_AVATAR_RATIO]
 * times the avatar and shares its centre, so it cannot be expressed as an ordinary constraint and is
 * laid out against the target instead. Declare it after the avatar so it paints on top, and before
 * the presence dot so that stays visible.
 *
 * Decoration assets are APNG, which Fresco decodes only the first frame of, so an animating frame is
 * fetched and decoded separately — the same split [NameplateView] makes. Both layers are created on
 * demand: most rows in a list have no decoration at all, and only the profile animates one.
 */
class AvatarDecorationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var image: SimpleDraweeView? = null
    private var animatedImage: ImageView? = null
    private val changeDetector = MGImages.DistinctChangeDetector()
    private var targetId = View.NO_ID
    private var targetView: View? = null
    private var decoration: AvatarDecorationData? = null
    private var animate = false

    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var animationJob: Job? = null
    @Volatile
    private var animationCall: Call? = null
    private var animatedDrawable: ApngDrawable? = null
    private var animationKey: String? = null

    private val parentLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        layoutToTarget()
    }

    // The parent lays this back out to its measured 0dp on every pass, including a RecyclerView
    // rebind that leaves the row's own bounds unchanged, so correct it whenever that happens.
    private val selfLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        layoutToTarget()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        visibility = GONE
        addOnLayoutChangeListener(selfLayoutListener)
        context.obtainStyledAttributes(attrs, R.styleable.AvatarDecorationView).run {
            try {
                targetId = getResourceId(R.styleable.AvatarDecorationView_avd_target, View.NO_ID)
            } finally {
                recycle()
            }
        }
    }

    @JvmOverloads
    fun bind(decoration: AvatarDecorationData?, animate: Boolean = false) {
        this.decoration = decoration
        this.animate = animate
        updateVisuals()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.let {
            it.removeOnLayoutChangeListener(parentLayoutListener)
            it.addOnLayoutChangeListener(parentLayoutListener)
            if (it.isLaidOut) layoutToTarget()
        }
        updateVisuals()
    }

    override fun onDetachedFromWindow() {
        (parent as? View)?.removeOnLayoutChangeListener(parentLayoutListener)
        targetView = null
        clearDecorationAnimation()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        image?.layout(0, 0, width, height)
        animatedImage?.layout(0, 0, width, height)
    }

    private fun layoutToTarget() {
        // Resolved once per attach: this runs on every layout pass of every row that has one.
        val target = targetView ?: (parent as? View)?.findViewById<View>(targetId)?.also { targetView = it }
        if (target == null || target.width == 0) return
        val margin = ((DECORATION_TO_AVATAR_RATIO - 1f) * target.width / 2f).toInt()
        val l = target.left - margin
        val t = target.top - margin
        val r = target.right + margin
        val b = target.bottom + margin
        if (left == l && top == t && right == r && bottom == b) return
        layout(l, t, r, b)
    }

    private fun updateVisuals() {
        val urls = decoration?.imageUrls(animate = false).orEmpty()
        if (urls.isEmpty()) {
            visibility = GONE
            image?.let { MGImages.setImage(it, emptyList(), changeDetector = changeDetector) }
            clearDecorationAnimation()
            return
        }
        val reducedMotion = runCatching { StoreStream.getAccessibility().isReducedMotionEnabled() }
            .getOrDefault(false)
        visibility = VISIBLE
        MGImages.setImage(staticLayer(), urls, changeDetector = changeDetector, autoPlayAnimations = false)
        updateAnimation(decoration.takeIf { animate && !reducedMotion })
        layoutToTarget()
    }

    private fun staticLayer(): SimpleDraweeView = image ?: SimpleDraweeView(context).also {
        image = it
        addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun animatedLayer(): ImageView = animatedImage ?: ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }.also {
        animatedImage = it
        addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun updateAnimation(decoration: AvatarDecorationData?) {
        val url = decoration?.imageUrls(animate = true)?.firstOrNull()
        if (url == null) {
            clearDecorationAnimation()
            return
        }
        if (url == animationKey) return
        clearDecorationAnimation()
        animationKey = url
        if (!isAttachedToWindow) return
        animationJob = animationScope.launch {
            var decoded: ApngDrawable? = null
            try {
                decoded = withContext(Dispatchers.IO) {
                    val call = CollectibleAssets.client.newCall(Request.Builder().url(url).build())
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
                if (url != animationKey) return@launch
                animatedDrawable?.let(::recycle)
                animatedDrawable = drawable
                decoded = null
                drawable.loopCount = ApngDrawable.LOOP_FOREVER
                animatedLayer().apply {
                    setImageDrawable(drawable)
                    layout(0, 0, this@AvatarDecorationView.width, this@AvatarDecorationView.height)
                    visibility = VISIBLE
                }
                drawable.start()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                decoded?.recycle()
            }
        }
    }

    private fun clearDecorationAnimation() {
        animationKey = null
        animationCall?.cancel()
        animationCall = null
        animationJob?.cancel()
        animationJob = null
        animatedImage?.let {
            it.visibility = INVISIBLE
            it.setImageDrawable(null)
        }
        animatedDrawable?.let(::recycle)
        animatedDrawable = null
    }

    private fun recycle(drawable: ApngDrawable) {
        drawable.stop()
        drawable.clearAnimationCallbacks()
        drawable.recycle()
    }

    private companion object {
        const val DECORATION_TO_AVATAR_RATIO = 1.2f
    }
}
