package com.discord.widgets.guilds.contextmenu

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.discord.app.AppFragment
import kotlin.math.hypot

/**
 * The sidebar's long-press menu: a card that circular-reveals from the left edge of the row that
 * was pressed.
 *
 * It is not a dialog. The sidebar is inside a panel that keeps sliding while the menu is up, so the
 * menu is a fragment added to a full-screen container in the activity's own view tree, positioned
 * at an absolute point. That is also why dismissal is a companion-level concern: the sidebar has to
 * be able to close a menu it does not own when a drag starts.
 *
 * Subclasses supply their card and are driven entirely by their own view model.
 */
abstract class AnchoredContextMenu(@LayoutRes contentLayoutId: Int) : AppFragment(contentLayoutId) {

    private var animator: Animator? = null
    private var animationState: AnimationState? = null

    /** The card to reveal; the rest of the fragment's view is transparent. */
    protected abstract val card: CardView

    /** Reveals the card, unless it is already visible. */
    internal fun revealCard() {
        if (card.visibility == View.VISIBLE) return
        if (!card.isAttachedToWindow) {
            card.visibility = View.VISIBLE
            return
        }
        val centerY = card.height / 2
        val radius = hypot(card.width.toFloat(), (card.height / 2).toFloat())
        animator = ViewAnimationUtils.createCircularReveal(card, 0, centerY, 0f, radius).apply {
            duration = ANIMATION_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    animationState = null
                }
            })
        }
        animationState = AnimationState.ANIMATING_IN
        card.visibility = View.VISIBLE
        animator?.start()
    }

    /**
     * Plays the reveal backwards and removes the menu.
     *
     * Interrupting a reveal that has not finished would look like a glitch, so a menu that is still
     * animating in is removed outright instead.
     */
    internal fun dismissCard(remove: (FragmentActivity) -> Unit) {
        when (animationState) {
            AnimationState.ANIMATING_IN -> {
                animator?.cancel()
                remove(requireActivity())
                return
            }
            AnimationState.ANIMATING_OUT -> return
            null -> Unit
        }
        if (!card.isAttachedToWindow) {
            card.visibility = View.GONE
            return
        }
        val centerY = card.height / 2
        val radius = hypot(card.width.toFloat(), (card.height / 2).toFloat())
        animator = ViewAnimationUtils.createCircularReveal(card, 0, centerY, radius, 0f).apply {
            duration = ANIMATION_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    activity?.let(remove)
                }
            })
        }
        animationState = AnimationState.ANIMATING_OUT
        animator?.start()
    }

    private enum class AnimationState { ANIMATING_IN, ANIMATING_OUT }

    /**
     * The show/hide half of a menu, shared by both concrete menus.
     *
     * [fragmentTag] and [viewContainerTag] identify the one instance that may be up at a time —
     * the flag alone is not enough, because the container view has to be found again to remove it.
     */
    abstract class Host(
        private val fragmentTag: String,
        private val viewContainerTag: String,
        @LayoutRes private val contentLayoutId: Int,
    ) {
        private var isShowingContextMenu = false

        protected abstract fun newInstance(id: Long): Fragment

        /**
         * @param absolutePosition where the pressed row is, in window coordinates. The menu is
         *   pushed up when it would otherwise run off the bottom of the screen.
         */
        fun show(activity: FragmentActivity, absolutePosition: PointF, id: Long) {
            if (isShowingContextMenu) return
            isShowingContextMenu = true
            hide(activity, animate = false)

            val root = activity.window.decorView.rootView as ViewGroup

            // A full-screen scrim that catches the tap that dismisses the menu.
            val container = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                tag = viewContainerTag
                setOnClickListener { hide(activity, animate = true) }
            }
            root.addView(container)

            val menuHost = FrameLayout(activity).apply {
                // Qualified: an outer `id: Long` in scope shadows View.id inside this apply block.
                this.id = View.generateViewId()
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            container.addView(menuHost)
            menuHost.x = absolutePosition.x
            menuHost.y = absolutePosition.y.coerceAtMost(
                (root.height - computeMaxContextMenuHeight(activity) - screenBottomBuffer(activity)).toFloat(),
            )

            activity.supportFragmentManager
                .beginTransaction()
                .add(menuHost.id, newInstance(id), fragmentTag)
                .commit()
        }

        /** [animate] plays the reveal backwards; false tears the menu down immediately. */
        fun hide(activity: FragmentActivity, animate: Boolean) {
            val fragment = activity.supportFragmentManager.findFragmentByTag(fragmentTag) ?: return
            if (animate) {
                (fragment as? AnchoredContextMenu)?.dismissCard { hide(it, animate = false) }
                return
            }
            activity.supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
            val root = activity.window.decorView.rootView as ViewGroup
            root.findViewWithTag<FrameLayout>(viewContainerTag)?.let(root::removeView)
            isShowingContextMenu = false
        }

        /** Measures a throwaway copy of the card so the menu can be kept on screen. */
        private fun computeMaxContextMenuHeight(activity: FragmentActivity): Int {
            val card = LayoutInflater.from(activity).inflate(contentLayoutId, null, false)
            card.measure(0, 0)
            return card.measuredHeight
        }

        private fun screenBottomBuffer(activity: FragmentActivity): Int =
            (SCREEN_BOTTOM_BUFFER_DP * activity.resources.displayMetrics.density).toInt()
    }

    private companion object {
        private const val ANIMATION_DURATION_MS = 200L

        /** Kept clear of the bottom navigation bar. */
        private const val SCREEN_BOTTOM_BUFFER_DP = 96
    }
}
