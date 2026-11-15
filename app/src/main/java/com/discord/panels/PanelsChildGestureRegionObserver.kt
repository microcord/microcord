package com.discord.panels

import android.view.View
import androidx.annotation.UiThread
import java.lang.ref.WeakReference

/**
 * Child views that must win horizontal gestures over [OverlappingPanelsLayout]: anything inside it
 * that scrolls or swipes sideways itself, like a `ViewPager2`, a scrolling `TabLayout` or the chat
 * input's attachment strip.
 *
 * The reference cached each view's window rectangle and refreshed it on layout and scroll changes.
 * Those rectangles went stale whenever a view moved without re-laying out (an ancestor resizing for
 * the keyboard) or was hidden without unregistering, and a stale one swallowed panel swipes over
 * whatever now sat there. Positions are read at touch time instead, and only for views that show.
 */
class PanelsChildGestureRegionObserver {

    private val views = ArrayList<WeakReference<View>>()
    private val location = IntArray(2)

    @UiThread
    fun register(view: View) {
        if (views.none { it.get() === view }) views += WeakReference(view)
    }

    @UiThread
    fun unregister(view: View) {
        views.removeAll { it.get().let { registered -> registered == null || registered === view } }
    }

    /** Whether a touch at screen position ([rawX], [rawY]) lands on a registered view that is showing. */
    @UiThread
    fun contains(rawX: Float, rawY: Float): Boolean = views.any { ref ->
        val view = ref.get()?.takeIf(View::isShown) ?: return@any false
        view.getLocationOnScreen(location)
        rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
    }

    object Provider {
        private val instance = PanelsChildGestureRegionObserver()

        @UiThread
        fun get(): PanelsChildGestureRegionObserver = instance
    }
}
