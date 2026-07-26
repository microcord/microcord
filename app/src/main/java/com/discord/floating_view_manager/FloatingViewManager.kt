package com.discord.floating_view_manager

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import com.discord.app.AppLog
import java.lang.ref.WeakReference
import rx.Observable

/**
 * Places arbitrary views on top of a screen, anchored to another view, and keeps them there.
 *
 * The floating view is added to the anchor's **root** view group rather than to the anchor's own
 * parent, so it is never clipped by a scroll container or a card. That means its position has to be
 * maintained by hand: an `OnPreDrawListener` on the root re-positions it before every frame, which
 * is what keeps a tooltip glued to a toolbar button while the list behind it scrolls.
 *
 * Floating views are keyed by [View.getId]. Adding a view whose id is already tracked repositions
 * the existing placement instead of adding a second copy — and, since the reference's default
 * tooltip layouts carry no `android:id`, every one of them collides on `View.NO_ID`, so only one
 * default tooltip can be on screen at a time. That is reference behaviour, not an accident here.
 */
class FloatingViewManager {

    /** Everything needed to undo one placement. */
    private data class FloatingViewData(
        val floatingView: View,
        val ancestorViewGroup: ViewGroup,
        val ancestorPreDrawListener: ViewTreeObserver.OnPreDrawListener,
    )

    private val floatingViews = LinkedHashMap<Int, FloatingViewData>()

    /** Invoked with the view id once a floating view has been detached. */
    var onFloatingViewRemoved: ((Int) -> Unit)? = null

    /**
     * Attaches [floatingView] to [anchorView]'s window and keeps it positioned against the anchor.
     *
     * The floating view starts invisible and is only revealed once both it and the anchor have been
     * measured, because the first position can only be computed from real sizes — otherwise the
     * first frame is drawn in the top-left corner.
     *
     * @param componentPausedObservable removes the floating view on its first emission, so a
     *   backgrounded screen does not leave a tooltip stranded on the one that replaced it.
     */
    @MainThread
    fun addFloatingView(
        anchorView: View,
        floatingView: View,
        floatingViewGravity: FloatingViewGravity,
        xOffset: Int,
        yOffset: Int,
        componentPausedObservable: Observable<Unit>,
    ) {
        val ancestorViewGroup = anchorView.rootView as ViewGroup

        floatingView.visibility = View.INVISIBLE
        if (!floatingViews.containsKey(floatingView.id)) {
            ancestorViewGroup.addView(floatingView)
        }

        floatingView.doOnLaidOut {
            anchorView.doOnLaidOut {
                position(floatingView, anchorView, floatingViewGravity, xOffset, yOffset)
                floatingView.visibility = View.VISIBLE
            }
        }

        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            position(floatingView, anchorView, floatingViewGravity, xOffset, yOffset)
            true
        }
        ancestorViewGroup.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        floatingViews[floatingView.id] =
            FloatingViewData(floatingView, ancestorViewGroup, preDrawListener)

        componentPausedObservable
            .take(1)
            .subscribe(
                { removeFloatingView(floatingView.id) },
                { throwable ->
                    AppLog.e(
                        TAG,
                        "error while subscribing to componentPausedObservable for hiding floating view",
                        throwable,
                    )
                },
            )
    }

    /** Detaches the floating view with [floatingViewId], if one is tracked. */
    @MainThread
    fun removeFloatingView(floatingViewId: Int) {
        val data = floatingViews.remove(floatingViewId) ?: return
        data.ancestorViewGroup.viewTreeObserver.removeOnPreDrawListener(data.ancestorPreDrawListener)
        data.ancestorViewGroup.removeView(data.floatingView)
        onFloatingViewRemoved?.invoke(floatingViewId)
    }

    /** Centres [floatingView] horizontally on [anchorView] and places it per [floatingViewGravity]. */
    private fun position(
        floatingView: View,
        anchorView: View,
        floatingViewGravity: FloatingViewGravity,
        xOffset: Int,
        yOffset: Int,
    ) {
        val anchorLocation = IntArray(2)
        anchorView.getLocationInWindow(anchorLocation)
        val anchorX = anchorLocation[0]
        val anchorY = anchorLocation[1]

        val x = anchorX + anchorView.width / 2 - floatingView.width / 2 + xOffset
        val y = when (floatingViewGravity) {
            FloatingViewGravity.TOP -> anchorY - floatingView.height
            FloatingViewGravity.CENTER -> anchorY + anchorView.height / 2 - floatingView.height / 2
            FloatingViewGravity.BOTTOM -> anchorY + anchorView.height
        }

        floatingView.x = x.toFloat()
        floatingView.y = (y + yOffset).toFloat()
    }

    /** Runs [action] immediately when the view is already laid out, otherwise after the next pass. */
    private fun View.doOnLaidOut(action: () -> Unit) {
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

    /**
     * Process-wide provider.
     *
     * The instance is held weakly, so it dies with the last screen holding a reference rather than
     * pinning every floating view's root view group for the life of the process.
     */
    object Provider {
        private var instance: WeakReference<FloatingViewManager>? = null

        @MainThread
        fun get(): FloatingViewManager {
            instance?.get()?.let { return it }
            return FloatingViewManager().also { instance = WeakReference(it) }
        }

        @VisibleForTesting
        internal fun resetForTest() {
            instance = null
        }
    }

    private companion object {
        private const val TAG = "FloatingViewManager"
    }
}
