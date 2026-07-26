package com.discord.overlay.views

import android.animation.Animator
import android.animation.AnimatorInflater
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import com.discord.R
import com.discord.overlay.TrashEventListener

/**
 * The "drop here to dismiss" target that slides up from the bottom of the screen while a bubble is
 * being dragged.
 *
 * Like a bubble, this is its own `TYPE_APPLICATION_OVERLAY` window — full width, pinned to the
 * bottom, and untouchable, because the drag gesture belongs to the bubble and this view only needs
 * to know *where* the finger is, never to receive the touch itself. [isInTrashZone] is the hit test
 * the owning `OverlayManager` runs against every move event.
 *
 * It is also a [TrashEventListener]: when a bubble enters the zone the target grows to swallow it
 * and the bubble is anchored to the trash icon, so the drop reads as the bubble being absorbed.
 */
class TrashWrap(context: Context) : FrameLayout(context), TrashEventListener {

    private val trashIcon: ImageView
    private val targetContainer: FrameLayout
    private val targetZone: FrameLayout

    /** Plays while a drag starts: the scrim fades in and the target slides up. */
    private val showAnimator: Animator

    /** Plays when the drag ends: the reverse, ending with the window hidden again. */
    private val hideAnimator: Animator

    /** Screen bounds of the drop zone, refreshed on every layout. */
    private val targetZoneBounds = Rect()

    private val targetZoneLocation = intArrayOf(0, 0)

    /** The bubble currently hovering the drop zone, if any. */
    private var bubbleInZone: OverlayBubbleWrap? = null

    /** Layout params of the trash window; the host reads these when adding it. */
    var windowLayoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayWindowType(),
        OVERLAY_WINDOW_FLAGS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        // The drag belongs to the bubble; this window must never steal the touch.
        addFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    init {
        LayoutInflater.from(getContext()).inflate(R.layout.trash_wrap, this)
        trashIcon = bindView(R.id.trash_wrap_icon)
        targetContainer = bindView(R.id.trash_wrap_target_container)
        targetZone = bindView(R.id.trash_wrap_target_zone)

        setBackgroundResource(R.drawable.trash_bg)
        visibility = INVISIBLE

        showAnimator = AnimatorSet().apply {
            playTogether(
                loadAnimator(R.animator.fade_in, this@TrashWrap),
                loadAnimator(R.animator.slide_in_bottom, targetZone),
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    visibility = VISIBLE
                }
            })
        }

        hideAnimator = AnimatorSet().apply {
            playTogether(
                loadAnimator(R.animator.fade_out, this@TrashWrap),
                loadAnimator(R.animator.slide_out_bottom, targetZone),
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = INVISIBLE
                }
            })
        }
    }

    /** Reveals the drop target; called as soon as a bubble starts moving. */
    fun show() = showAnimator.start()

    /** Hides the drop target again once the drag is over. */
    fun hide() = hideAnimator.start()

    /** Whether the screen point ([x], [y]) is inside the drop zone. */
    fun isInTrashZone(x: Int, y: Int): Boolean = targetZoneBounds.contains(x, y)

    /**
     * Grows the target around [bubble] and parks the bubble on the trash icon, or restores the
     * resting state when [bubble] is `null` because the drag left the zone.
     */
    override fun onBubbleInTrashZoneChanged(bubble: OverlayBubbleWrap?) {
        if (bubble != null) {
            targetContainer.isActivated = true
            // Grow far enough to fully contain the bubble, plus half again so it reads as swallowed.
            val bubbleExtent = maxOf(bubble.width, bubble.height)
            val targetScale =
                maxOf(bubbleExtent, targetContainer.width).toFloat() / targetContainer.width + 0.5f
            targetContainer.startAnimation(
                scaleTo(targetScale, android.R.interpolator.overshoot),
            )
        } else {
            targetContainer.isActivated = false
            isActivated = false
            targetContainer.startAnimation(
                scaleTo(1f, android.R.interpolator.anticipate),
            )
        }

        if (bubble != bubbleInZone) {
            bubble?.anchorTo(trashIcon)
            performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    HapticFeedbackConstants.CONTEXT_CLICK
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                },
            )
            // Whatever was in the zone before is released without animating — it is either gone or
            // already being dragged elsewhere.
            bubbleInZone?.unanchor(animate = false)
            bubbleInZone = bubble
        }
    }

    /** Nothing to do here — the host removes the bubble; the target just goes back to resting. */
    override fun onBubbleDismissed(bubble: OverlayBubbleWrap) = Unit

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!changed) return
        targetZone.getLocationOnScreen(targetZoneLocation)
        val (zoneX, zoneY) = targetZoneLocation
        targetZoneBounds.set(
            zoneX,
            zoneY,
            zoneX + targetZone.width,
            zoneY + targetZone.height,
        )
    }

    private fun scaleTo(scale: Float, interpolatorRes: Int): ScaleAnimation = ScaleAnimation(
        targetContainer.scaleX,
        scale,
        targetContainer.scaleY,
        scale,
        targetContainer.pivotX,
        targetContainer.pivotY,
    ).apply {
        duration = SCALE_DURATION_MS
        setInterpolator(context, interpolatorRes)
        fillAfter = true
    }

    private fun loadAnimator(animatorRes: Int, target: Any): Animator =
        AnimatorInflater.loadAnimator(context, animatorRes).apply { setTarget(target) }

    private fun <T : android.view.View> bindView(id: Int): T =
        requireNotNull(findViewById<T>(id)) {
            "Missing required view with ID: ${resources.getResourceName(id)}"
        }

    private companion object {
        private const val SCALE_DURATION_MS = 200L
    }
}
