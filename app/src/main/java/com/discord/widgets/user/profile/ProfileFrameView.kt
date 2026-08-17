package com.discord.widgets.user.profile

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.discord.api.collectibles.ProfileFrameItem
import com.discord.api.collectibles.ProfileFrameLayer
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.image.ImageInfo

/**
 * The collectible frame drawn around a profile.
 *
 * A frame is a stack of images measured against a reference profile width, so every layer is scaled
 * by `width / innerWidth` and allowed to hang outside the profile by the frame's own overflow. Only
 * top-anchored staples are drawn: rails and borders belong to the shop's tall preview card.
 *
 * [half] splits one frame across two of these — the back behind the profile's content and the front
 * over it — because a single view cannot sit on both sides of its sibling.
 */
class ProfileFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Which half of the frame this view draws; a preview has no content to sit either side of. */
    enum class Half { FRONT, BACK, BOTH }

    var half: Half = Half.FRONT
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    private var frame: ProfileFrameItem? = null
    private val layerViews = mutableListOf<LayerView>()
    private var anchorLeft = 0
    private var anchorTop = 0
    private var anchorWidth = 0

    /**
     * Where the profile's own top edge sits inside this view.
     *
     * A staple is meant to hang above that edge, over whatever is behind the profile. Drawing it
     * outside this view's bounds would just be clipped by an ancestor, so instead the view is given
     * the whole area it may draw in and the profile's edge is reported to it.
     */
    fun anchorTo(left: Int, top: Int, width: Int) {
        if (left == anchorLeft && top == anchorTop && width == anchorWidth) return
        anchorLeft = left
        anchorTop = top
        anchorWidth = width
        positionLayers()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        visibility = GONE
    }

    fun bind(frame: ProfileFrameItem?) {
        if (frame == this.frame) return
        this.frame = frame
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        layerViews.clear()
        val current = frame
        val layers = current?.topStaples()
            ?.filter { half == Half.BOTH || it.isFront == (half == Half.FRONT) }
            .orEmpty()
        if (current == null || layers.isEmpty()) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        layers.forEach { layer ->
            val view = LayerView(context)
            layerViews += view
            addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            view.load(current.skuId, layer)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionLayers()
    }

    private fun positionLayers() {
        val current = frame ?: return
        val containerWidth = anchorWidth.takeIf { it > 0 } ?: width
        if (containerWidth == 0 || current.innerWidth == 0) return
        val scale = containerWidth.toFloat() / current.innerWidth
        val overflowHorizontal = (current.overflowHorizontal * scale).toInt()
        val overflowTop = (current.overflowTop * scale).toInt()
        val layerWidth = containerWidth + overflowHorizontal * 2
        val left = anchorLeft - overflowHorizontal
        val top = anchorTop - overflowTop

        layerViews.forEach { view ->
            val ratio = view.aspectRatio ?: return@forEach
            val layerHeight = (layerWidth * ratio).toInt()
            view.layout(left, top, left + layerWidth, top + layerHeight)
        }
    }

    /** A single frame image, which cannot be positioned until its own proportions are known. */
    private inner class LayerView(context: Context) : SimpleDraweeView(context) {

        var aspectRatio: Float? = null
            private set

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            hierarchy.setActualImageScaleType(com.facebook.drawee.drawable.ScalingUtils.ScaleType.FIT_XY)
        }

        fun load(skuId: Long, layer: ProfileFrameLayer) {
            val url = layer.assetUrl(skuId)
            controller = Fresco.newDraweeControllerBuilder()
                .setUri(url)
                .setOldController(controller)
                .setControllerListener(
                    object : BaseControllerListener<ImageInfo>() {
                        override fun onFinalImageSet(
                            id: String?,
                            imageInfo: ImageInfo?,
                            animatable: android.graphics.drawable.Animatable?,
                        ) {
                            val info = imageInfo ?: return
                            if (info.width <= 0) return
                            aspectRatio = info.height.toFloat() / info.width
                            positionLayers()
                        }
                    },
                )
                .build()
        }
    }
}
