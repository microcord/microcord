package com.discord.overlay

import android.animation.Animator
import android.animation.AnimatorInflater
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.overlay.views.OverlayBubbleWrap
import com.discord.overlay.views.TrashWrap
import com.discord.overlay.views.doOnLaidOut
import com.discord.utilities.display.getScreenSize
import java.io.Closeable

/**
 * Owns every overlay window on screen and the interactions between them.
 *
 * Each bubble is its own `TYPE_APPLICATION_OVERLAY` window, so nothing above the [WindowManager]
 * knows they are related. This class is that missing layer: it keeps the list of live bubbles, wires
 * the drag/trash gesture, and knows how to open a menu bubble anchored to the one that spawned it.
 *
 * It is [Closeable] because the host is a `Service` — [close] must detach every window, or they
 * outlive the process that put them there.
 */
open class OverlayManager @JvmOverloads constructor(
    protected val context: Context,
    protected val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager,
) : Closeable {

    private val overlaysOnDisplay = ArrayList<OverlayBubbleWrap>(INITIAL_BUBBLE_CAPACITY)

    /** Bubbles currently attached to the window, in the order they were added. */
    val activeBubbles: List<OverlayBubbleWrap> get() = overlaysOnDisplay

    /** Invoked after a bubble has been attached to the window. */
    var onOverlayBubbleAdded: (View) -> Unit = {}

    /**
     * Invoked after a bubble has been detached.
     *
     * The host uses this to stop itself once the last bubble is gone.
     */
    var onOverlayBubbleRemoved: (View) -> Unit = {}

    /** The drop target, once the host has installed one with [setTrashWrap]. */
    var trashWrap: TrashWrap? = null
        private set

    /** Host-side counterpart of the trash target's own [TrashEventListener]. */
    var trashEventListener: TrashEventListener? = null

    /** The bubble currently hovering the drop zone, tracked so transitions fire exactly once. */
    private var bubbleInTrashZone: OverlayBubbleWrap? = null

    /**
     * Watches a dragged bubble against the trash zone.
     *
     * Returns `true` only for the drop itself, which consumes the `ACTION_UP` so the bubble's own
     * click listener does not also fire.
     */
    private val bubbleOnTouchListener = View.OnTouchListener { view, event ->
        if (event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_MOVE) {
            return@OnTouchListener false
        }

        val overTrash = trashWrap?.isInTrashZone(event.rawX.toInt(), event.rawY.toInt()) == true
        if (!overTrash) {
            setBubbleInTrashZone(null)
            return@OnTouchListener false
        }

        val bubble = view as OverlayBubbleWrap
        if (event.action == MotionEvent.ACTION_UP) {
            dismissBubbleIntoTrash(bubble)
            return@OnTouchListener true
        }
        setBubbleInTrashZone(bubble)
        false
    }

    /** Attaches [bubble] to the window and announces it. */
    fun addBubble(bubble: OverlayBubbleWrap) {
        windowManager.addView(bubble, bubble.windowLayoutParams)
        overlaysOnDisplay.add(bubble)
        onOverlayBubbleAdded(bubble)
    }

    /** Detaches [bubble], if this manager still owns it. */
    fun removeBubble(bubble: OverlayBubbleWrap) {
        if (!overlaysOnDisplay.remove(bubble)) return
        windowManager.removeViewImmediate(bubble)
        onOverlayBubbleRemoved(bubble)
    }

    /**
     * Springs [bubble] to whichever vertical screen edge it is closest to, keeping its height.
     *
     * The out-of-range target is deliberate — [OverlayBubbleWrap.springToPosition] clamps it to the
     * real edge, so "as far right as allowed" is expressible without measuring anything here.
     */
    fun springBubbleToEdge(bubble: OverlayBubbleWrap) {
        val targetX =
            if (bubble.centerX > getScreenSize(context).centerX()) Int.MAX_VALUE else Int.MIN_VALUE
        bubble.springToPosition(targetX, bubble.getY().toInt())
    }

    /**
     * Makes [bubble] draggable: it follows the finger, reports hover over the trash target, and
     * snaps back to an edge when released.
     */
    fun attachBubbleGestures(bubble: OverlayBubbleWrap) {
        bubble.setOnTouchListener(bubbleOnTouchListener)
        bubble.onMovingStateChanged = { moving ->
            if (moving) {
                trashWrap?.show()
            } else {
                trashWrap?.hide()
                springBubbleToEdge(bubble)
            }
        }
    }

    /**
     * Makes tapping [srcBubble] open the bubble produced by [menuBubbleProvider], with [srcBubble]
     * flying into the slot the menu reserves for it.
     *
     * @param anchorViewTag `View` tag of the placeholder inside the menu that [srcBubble] docks onto.
     */
    fun setMenuBubbleOnClick(
        srcBubble: OverlayBubbleWrap,
        anchorViewTag: Any,
        menuBubbleProvider: (OverlayBubbleWrap) -> OverlayBubbleWrap,
    ) {
        srcBubble.setOnClickListener {
            val menuBubble = menuBubbleProvider(srcBubble)
            menuBubble.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    // The anchor slot only has bounds once the menu has been laid out.
                    view.doOnLaidOut {
                        srcBubble.anchorTo(view.findViewWithTag<View>(anchorViewTag))
                    }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    if (!ViewCompat.isAttachedToWindow(srcBubble)) return
                    srcBubble.setBubbleTouchable(true)
                    srcBubble.unanchor(animate = true)
                }
            })
            addBubble(menuBubble)

            // The source bubble must sit above the menu it opened, and a window's Z order is its
            // insertion order — so it is detached and re-attached rather than merely raised.
            srcBubble.setBubbleTouchable(false)
            windowManager.removeViewImmediate(srcBubble)
            windowManager.addView(srcBubble, srcBubble.windowLayoutParams)
        }
    }

    /** Installs the drop target and attaches its window. */
    fun setTrashWrap(trashWrap: TrashWrap) {
        this.trashWrap = trashWrap
        windowManager.addView(trashWrap, trashWrap.windowLayoutParams)
    }

    /**
     * Records which bubble — if any — is hovering the drop zone, and fans the change out to both
     * listeners. Repeated calls with the same bubble are ignored.
     */
    private fun setBubbleInTrashZone(bubble: OverlayBubbleWrap?) {
        if (bubbleInTrashZone == bubble) return
        bubbleInTrashZone = bubble
        trashWrap?.onBubbleInTrashZoneChanged(bubble)
        trashEventListener?.onBubbleInTrashZoneChanged(bubble)
    }

    /**
     * Plays the "consumed by the trash" animation for [bubble] and hands it to the listeners for
     * removal once the animation ends.
     *
     * The bubble is dropped while anchored to the trash icon, so the anchor is adopted as its real
     * position first — otherwise the fade-out would happen back at the drag's origin.
     */
    private fun dismissBubbleIntoTrash(bubble: OverlayBubbleWrap) {
        bubble.commitAnchorAsPosition()
        setBubbleInTrashZone(null)

        val fadeOut: Animator = AnimatorInflater.loadAnimator(bubble.context, R.animator.fade_out)
        fadeOut.setTarget(bubble)
        fadeOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                trashWrap?.onBubbleDismissed(bubble)
                trashEventListener?.onBubbleDismissed(bubble)
            }
        })
        fadeOut.start()

        // Sink it half its own height while it fades, so it reads as falling into the target.
        bubble.springToPosition(bubble.getX().toInt(), bubble.getY().toInt() + bubble.height / 2)
    }

    /**
     * Detaches every window this manager owns.
     *
     * A bubble whose window is already gone throws [IllegalArgumentException] from
     * [WindowManager.removeViewImmediate]; that bubble is left in the list rather than reported as
     * removed, which mirrors the reference and keeps a partial teardown from lying about what it
     * detached.
     */
    override fun close() {
        overlaysOnDisplay.removeAll { bubbleWrap ->
            try {
                windowManager.removeViewImmediate(bubbleWrap)
                onOverlayBubbleRemoved(bubbleWrap)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }
        trashWrap?.let(windowManager::removeView)
        trashWrap = null
    }

    private companion object {
        /** The reference sizes the backing list for the handful of bubbles that can coexist. */
        private const val INITIAL_BUBBLE_CAPACITY = 5
    }
}
