package com.discord.overlay.views

import android.animation.AnimatorInflater
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.CallSuper
import androidx.core.view.ViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.discord.R
import com.discord.utilities.display.getScreenSize
import kotlin.properties.Delegates

/**
 * A draggable chat-head style window.
 *
 * Every bubble owns its own `TYPE_APPLICATION_OVERLAY` window, so it lives outside the activity
 * hierarchy entirely. That has two consequences the rest of this class exists to paper over:
 *
 * * **Position is a window property, not a view property.** `View.setX`/`setY` are overridden to
 *   write [windowLayoutParams] and push them through the [WindowManager] instead of translating the
 *   view inside a parent that does not exist. This is what lets a plain [SpringAnimation] on
 *   [DynamicAnimation.X] fling the whole window.
 * * **There is no parent to convert coordinates against.** [screenOffset] records the delta between
 *   where the platform actually placed the window and the layout params we asked for (the status
 *   bar being the usual culprit) and is refreshed on every layout and configuration change.
 *
 * Dragging is detected here rather than through a gesture detector: a touch only becomes a drag once
 * it travels past [moveThresholdPx], which leaves short taps to the bubble's own click listener.
 * While [isMoving] the bubble follows the finger; on release the owning `OverlayManager` springs it
 * to the nearest screen edge.
 */
open class OverlayBubbleWrap(context: Context) : FrameLayout(context) {

    /** The window this bubble is attached to. */
    val windowManager: WindowManager =
        getContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * Layout params of the bubble's own window.
     *
     * Mutable because subclasses reshape the window — [OverlayDialog] makes it full-screen and dims
     * behind it — and because dragging writes `x`/`y` straight into it.
     */
    var windowLayoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayWindowType(),
        OVERLAY_WINDOW_FLAGS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.LEFT }

    /**
     * Extra margins kept clear at each screen edge when the bubble is snapped or clamped.
     *
     * Callers mutate this rectangle in place — the voice bubble insets it horizontally so it can
     * hang slightly off-screen, and the menu dialog insets the top so it clears the status bar.
     */
    val insetMargins: Rect = Rect()

    /** Distance a touch must travel before it counts as a drag rather than a tap. */
    val moveThresholdPx: Int = resources.getDimensionPixelOffset(R.dimen.movement_threshold_dp)

    /**
     * Whether the bubble is currently following the finger.
     *
     * Open because [OverlayDialog] pins it to `false`: a full-screen dialog is not draggable.
     */
    open var isMoving: Boolean by Delegates.observable(false) { _, wasMoving, moving ->
        if (wasMoving == moving) return@observable
        onMovingStateChanged?.invoke(moving)
        if (moving) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            // A drag beats any in-flight snap animation.
            springAnimationX.cancel()
            springAnimationY.cancel()
        }
        isPressed = moving
    }

    /** Notified whenever [isMoving] flips, so the host can show and hide the trash target. */
    var onMovingStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Difference between the window's on-screen origin and its layout-param origin.
     *
     * `[0]` is the horizontal offset and `[1]` the vertical one; both are refreshed by
     * [updateScreenOffset].
     */
    val screenOffset: IntArray = IntArray(2)

    /** Finger-to-window offsets captured on `ACTION_DOWN` so a drag does not jump. */
    private var deltaX: Int = 0
    private var deltaY: Int = 0

    /**
     * Horizontal spring used for every non-drag movement.
     *
     * Exposed so the host can cancel an in-flight snap, but treated as an implementation detail:
     * position changes should go through [springToPosition] or [setX].
     */
    val springAnimationX: SpringAnimation = SpringAnimation(this, DynamicAnimation.X, 0f).apply {
        setStartVelocity(SPRING_START_VELOCITY)
        spring.stiffness = SPRING_STIFFNESS
        spring.dampingRatio = SPRING_DAMPING_RATIO
    }

    /** Vertical counterpart of [springAnimationX]. */
    val springAnimationY: SpringAnimation = SpringAnimation(this, DynamicAnimation.Y, 0f).apply {
        setStartVelocity(SPRING_START_VELOCITY)
        spring.dampingRatio = SPRING_DAMPING_RATIO
        spring.stiffness = SPRING_STIFFNESS
    }

    /**
     * Where the bubble would sit if it were not anchored.
     *
     * Dragging writes here directly; anchoring remembers it so [unanchor] can spring back.
     */
    private var actualPosition: Point = Point(windowLayoutParams.x, windowLayoutParams.y)

    /** Non-null while the bubble is parked on top of another view — see [anchorTo]. */
    private var anchorPosition: Point? = null

    init {
        stateListAnimator =
            AnimatorInflater.loadStateListAnimator(getContext(), R.drawable.bubble_state_selector)
    }

    /** Centre of the bubble's window in screen coordinates. */
    val centerX: Int
        get() = windowLayoutParams.x + width / 2

    /** Centre of the bubble's window in screen coordinates. */
    val centerY: Int
        get() = windowLayoutParams.y + height / 2

    /**
     * The area the bubble is allowed to occupy: the whole screen, less [insetMargins].
     *
     * Protected rather than private because subclasses that reshape the window need to clamp against
     * a different rectangle.
     */
    protected fun getScreenBounds(context: Context): Rect = getScreenSize(context).apply {
        left += insetMargins.left
        right -= insetMargins.right
        top += insetMargins.top
        bottom -= insetMargins.bottom
    }

    /**
     * Springs the bubble to ([x], [y]), clamped so it stays inside [screenBounds].
     *
     * Callers pass deliberately out-of-range coordinates to mean "as far that way as allowed":
     * `OverlayManager` snaps a released bubble to an edge with [Int.MAX_VALUE]/[Int.MIN_VALUE].
     */
    fun springToPosition(x: Int, y: Int, screenBounds: Rect = getScreenBounds(context)) {
        val targetX = minOf(maxOf(screenBounds.left, x), screenBounds.right - width)
        springAnimationX.animateFromLayoutTo(windowLayoutParams.x, targetX)

        // The vertical bound is measured in window space, so the screen offset has to come out.
        val targetY = minOf(maxOf(screenBounds.top - screenOffset[1], y), screenBounds.bottom - height)
        springAnimationY.animateFromLayoutTo(windowLayoutParams.y, targetY)
    }

    /**
     * Parks the bubble on top of [targetView], centre over centre.
     *
     * Used when the expanded menu opens: the collapsed bubble flies into the placeholder slot the
     * menu reserves for it, and stays there until [unanchor].
     */
    open fun anchorTo(targetView: View) {
        val targetLocation = IntArray(2)
        targetView.getLocationOnScreen(targetLocation)
        val targetCenterX = targetLocation[0] + targetView.width / 2
        val targetCenterY = targetLocation[1] + targetView.height / 2

        val anchorPoint = Point(
            targetCenterX - screenOffset[0] - width / 2,
            targetCenterY - screenOffset[1] - height / 2,
        )
        setAnchorAt(anchorPoint)
        springToPosition(anchorPoint.x, anchorPoint.y)
        Log.d(javaClass.simpleName, "Anchored[$actualPosition] -> $targetView")
    }

    /**
     * Records that the bubble is now anchored at [newAnchorPoint], remembering where it was so
     * [unanchor] can put it back.
     */
    fun setAnchorAt(newAnchorPoint: Point) {
        actualPosition.x = getX().toInt()
        actualPosition.y = getY().toInt()
        anchorPosition = newAnchorPoint
        Log.d(javaClass.simpleName, "Anchoring[$actualPosition] -> $anchorPosition")
    }

    /**
     * Adopts the current anchor as the bubble's real resting position.
     *
     * Called when a bubble is dropped while anchored — for example onto the trash target — so that
     * releasing the anchor afterwards does not fling it back to where the drag started.
     */
    internal fun commitAnchorAsPosition() {
        val anchor = anchorPosition ?: return
        actualPosition.x = anchor.x
        actualPosition.y = anchor.y
        Log.d(javaClass.simpleName, "Moved to anchor [$actualPosition]")
    }

    /**
     * Releases an anchor and returns the bubble to where it was before [anchorTo].
     *
     * @param animate `false` teleports it, which is what happens when the bubble is being consumed
     *   by the trash target and the move must not be visible.
     */
    open fun unanchor(animate: Boolean = true) {
        Log.d(javaClass.simpleName, "Unanchoring[$anchorPosition] -> $actualPosition")
        anchorPosition = null
        springAnimationX.cancel()
        springAnimationY.cancel()

        if (animate) {
            springToPosition(actualPosition.x, actualPosition.y)
        } else {
            windowLayoutParams.x = actualPosition.x
            windowLayoutParams.y = actualPosition.y
            windowManager.updateViewLayout(this, windowLayoutParams)
        }
    }

    /**
     * Makes the bubble ignore touches entirely, letting them fall through to whatever is behind it.
     *
     * Used while an expanded dialog owns the interaction.
     */
    fun setBubbleTouchable(isTouchable: Boolean) {
        windowLayoutParams.setFlagTouchable(isTouchable)
        windowManager.updateViewLayout(this, windowLayoutParams)
    }

    /**
     * Recomputes [screenOffset].
     *
     * Must run after every layout, because the platform is free to place the window somewhere other
     * than the requested `x`/`y` — and every coordinate conversion below depends on knowing by how
     * much.
     */
    private fun updateScreenOffset() {
        getLocationOnScreen(screenOffset)
        screenOffset[0] -= windowLayoutParams.x
        screenOffset[1] -= windowLayoutParams.y
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) updateScreenOffset()
    }

    @CallSuper
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        windowManager.updateViewLayout(this, windowLayoutParams)
        doOnLaidOut { updateScreenOffset() }
    }

    override fun dispatchTouchEvent(motionEvent: MotionEvent): Boolean {
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> {
                deltaX = motionEvent.rawX.toInt() - windowLayoutParams.x
                deltaY = motionEvent.rawY.toInt() - windowLayoutParams.y
            }

            MotionEvent.ACTION_UP -> if (isMoving) isMoving = false

            MotionEvent.ACTION_MOVE -> if (!isMoving) {
                // Only horizontal travel arms the drag; a vertical-only swipe is left to children.
                val travelled = deltaX + windowLayoutParams.x - motionEvent.rawX.toInt()
                if (Math.abs(travelled) > moveThresholdPx) isMoving = true
            } else {
                actualPosition.x = motionEvent.rawX.toInt() - deltaX
                actualPosition.y = motionEvent.rawY.toInt() - deltaY
                // While anchored the anchor owns the position, so the drag is only recorded.
                if (ViewCompat.isAttachedToWindow(this) && anchorPosition == null) {
                    windowLayoutParams.x = actualPosition.x
                    windowLayoutParams.y = actualPosition.y
                    windowManager.updateViewLayout(this, windowLayoutParams)
                }
            }
        }
        return super.dispatchTouchEvent(motionEvent)
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean =
        if (motionEvent.action == MotionEvent.ACTION_MOVE) isMoving
        else super.onInterceptTouchEvent(motionEvent)

    // The bubble's position lives in its window, not in a parent, so X/Y are redirected wholesale.

    override fun getX(): Float = windowLayoutParams.x.toFloat()

    override fun setX(x: Float) {
        windowLayoutParams.x = x.toInt()
        if (ViewCompat.isAttachedToWindow(this)) {
            windowManager.updateViewLayout(this, windowLayoutParams)
        }
    }

    override fun getY(): Float = windowLayoutParams.y.toFloat()

    override fun setY(y: Float) {
        windowLayoutParams.y = y.toInt()
        if (ViewCompat.isAttachedToWindow(this)) {
            windowManager.updateViewLayout(this, windowLayoutParams)
        }
    }

    private companion object {
        /** Matches the spring feel of the reference's bubble: quick, with a small overshoot. */
        private const val SPRING_START_VELOCITY = 50f
        private const val SPRING_STIFFNESS = 200f
        private const val SPRING_DAMPING_RATIO = 0.75f
    }
}

/**
 * Window flags shared by every overlay window.
 *
 * Not focusable and not touch-modal, so the app behind keeps receiving input everywhere the overlay
 * is not; no layout limits, so a bubble may be dragged partly off-screen; hardware accelerated,
 * because the springs animate the window itself.
 */
internal val OVERLAY_WINDOW_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

/**
 * The window type an overlay may use on this platform version.
 *
 * `TYPE_APPLICATION_OVERLAY` only exists from O; before that the app used the deprecated
 * `TYPE_PRIORITY_PHONE`, which the same `SYSTEM_ALERT_WINDOW` permission covers.
 */
@Suppress("DEPRECATION")
internal fun overlayWindowType(): Int =
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
        WindowManager.LayoutParams.TYPE_PRIORITY_PHONE
    } else {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

/** Adds [flag] to the window flags, leaving the others alone. */
internal fun WindowManager.LayoutParams.addFlag(flag: Int) {
    flags = flags or flag
}

/** Clears [flag] from the window flags, leaving the others alone. */
internal fun WindowManager.LayoutParams.removeFlag(flag: Int) {
    flags = flags and flag.inv()
}

/** Sets or clears `FLAG_NOT_TOUCHABLE` — note the inversion, the flag is the negative form. */
internal fun WindowManager.LayoutParams.setFlagTouchable(isTouchable: Boolean) {
    if (isTouchable) {
        removeFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    } else {
        addFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }
}

/**
 * Restarts this spring from [startValue] and settles it on [finalValue].
 *
 * The explicit start value is required because the animated property is the *window's* position,
 * which the spring cannot read back from the view.
 */
internal fun SpringAnimation.animateFromLayoutTo(startValue: Int, finalValue: Int) {
    cancel()
    setStartValue(startValue.toFloat())
    animateToFinalPosition(finalValue.toFloat())
}

/** Runs [action] now if the view is already laid out, otherwise after the next layout pass. */
internal fun View.doOnLaidOut(action: () -> Unit) {
    if (ViewCompat.isLaidOut(this) && !isLayoutRequested) {
        action()
        return
    }
    addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            view: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        ) {
            view.removeOnLayoutChangeListener(this)
            action()
        }
    })
}
