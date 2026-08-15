package com.discord.widgets.notice

import android.animation.Animator
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.LayoutRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePaddingRelative
import com.discord.R
import com.discord.databinding.AlerterAlertViewBinding
import kotlin.math.abs

/**
 * The banner that slides down over the top of the screen for an in-app notification.
 *
 * ### Why this class exists
 * The reference builds this with the third-party **Alerter** library (`com.tapadoo.alerter`) and
 * only ever uses four things from it: attach a view to the activity's decor view, play an enter
 * animation, dismiss it on a horizontal swipe, and report when it went away. Alerter is not a
 * dependency of this project, and the two resources it needs — [R.layout.alerter_alert_view] and
 * the `alerter_*` dimens/styles — were recovered from the APK and are already in `res/`, so the
 * view hierarchy here is the reference's hierarchy exactly. Only the ~100 lines of behaviour around
 * it are ours.
 *
 * The consequence is that [NoticePopup.dismiss] cannot rely on `instanceof Alert` the way the
 * reference does; it looks for [NoticeAlertView] children of the decor view instead. That is the
 * only observable difference: the reference would also sweep away an alert raised by some *other*
 * caller of Alerter, and there are none.
 *
 * @param contentLayoutId inflated into the `vAlertContentContainer` stub, exactly as Alerter's
 *   `Alert(context, layoutId)` constructor does. For notices this is `R.layout.widget_notice_popup`.
 */
internal class NoticeAlertView(
    context: Context,
    @LayoutRes contentLayoutId: Int,
) : FrameLayout(context) {

    /** The inflated [contentLayoutId] — what callers bind their views against. */
    val layoutContainer: View

    /** The padded, coloured card. Clicks and swipes are handled here, not on the whole overlay. */
    private val binding = AlerterAlertViewBinding.inflate(LayoutInflater.from(context), this, true)
    private val alertBackground: LinearLayout = binding.llAlertBackground

    /** Runs when the banner goes away by itself: swipe, or [hide]. Not run by a forced removal. */
    var onHideListener: (() -> Unit)? = null

    private var enterAnimation: Animation =
        AnimationUtils.loadAnimation(context, R.anim.alerter_slide_in_from_top)

    private var isHiding = false

    init {
        layoutContainer = binding.vAlertContentContainer.run {
            layoutResource = contentLayoutId
            inflate()
        }
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        alertBackground.setOnTouchListener(SwipeToDismissTouchListener())
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            alertBackground.updatePaddingRelative(
                top = (topInset - resources.getDimensionPixelSize(R.dimen.alerter_padding_small)).coerceAtLeast(0),
            )
            insets
        }
    }

    fun setAlertBackgroundColor(@ColorInt color: Int) = alertBackground.setBackgroundColor(color)

    fun setEnterAnimation(animation: Animation) {
        enterAnimation = animation
    }

    /**
     * Sets the tap handler on the card rather than on `this`, because `this` is the full-width
     * transparent shield and a tap on the empty area beside the card must not count as a tap on the
     * notification.
     */
    fun setAlertClickListener(listener: OnClickListener?) {
        alertBackground.setOnClickListener(listener)
    }

    /** Slides the banner back up and detaches it, reporting through [onHideListener] once. */
    fun hide() {
        if (isHiding) return
        isHiding = true
        val exit = AnimationUtils.loadAnimation(context, R.anim.alerter_slide_out_to_top)
        exit.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) = Unit
            override fun onAnimationRepeat(animation: Animation?) = Unit
            override fun onAnimationEnd(animation: Animation?) {
                removeFromParent()
                onHideListener?.invoke()
            }
        })
        startAnimation(exit)
    }

    /**
     * Fades the banner out and detaches it **without** reporting through [onHideListener].
     *
     * This is the path taken when a notice is replaced or explicitly dismissed: the caller has
     * already decided what to tell the store, and letting the hide listener also fire would mark a
     * second, unrelated notice seen.
     */
    fun fadeOutAndRemove() {
        if (isHiding) return
        isHiding = true
        ViewCompat.animate(this).alpha(0f).withEndAction(::removeFromParent)
    }

    private fun removeFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
        startAnimation(enterAnimation)
    }

    /**
     * Horizontal fling-away dismissal, matching Alerter's own gesture.
     *
     * The card follows the finger and snaps back unless it has been dragged past a third of its
     * width, in which case it continues off-screen and hides. Returning `false` until the drag
     * actually starts is what lets the card's own click listener still fire on a tap.
     */
    private inner class SwipeToDismissTouchListener : OnTouchListener {
        private var downX = 0f
        private var swiping = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    swiping = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawX - downX
                    if (!swiping && abs(delta) > TOUCH_SLOP_PX) swiping = true
                    if (swiping) {
                        view.translationX = delta
                        view.alpha = 1f - (abs(delta) / view.width).coerceIn(0f, 1f)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!swiping) return false
                    val delta = event.rawX - downX
                    if (abs(delta) > view.width / DISMISS_FRACTION) {
                        view.animate()
                            .translationX(if (delta > 0) view.width.toFloat() else -view.width.toFloat())
                            .alpha(0f)
                            .setListener(object : SimpleAnimatorListener() {
                                override fun onAnimationEnd(animation: Animator) = hide()
                            })
                    } else {
                        view.animate().translationX(0f).alpha(1f).setListener(null)
                    }
                    return true
                }
            }
            return false
        }
    }

    private abstract class SimpleAnimatorListener : Animator.AnimatorListener {
        override fun onAnimationStart(animation: Animator) = Unit
        override fun onAnimationCancel(animation: Animator) = Unit
        override fun onAnimationRepeat(animation: Animator) = Unit
    }

    private companion object {
        /** A drag past a third of the card's width commits to the dismissal. */
        const val DISMISS_FRACTION = 3

        /** Enough movement to be sure the user is dragging rather than tapping. */
        const val TOUCH_SLOP_PX = 16f
    }
}
