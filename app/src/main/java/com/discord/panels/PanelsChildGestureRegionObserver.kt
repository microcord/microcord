package com.discord.panels

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.UiThread
import com.discord.app.AppLog
import java.lang.ref.WeakReference

/**
 * Collects the screen rectangles of child views that must win horizontal gestures over
 * [OverlappingPanelsLayout].
 *
 * The panels layout steals every horizontal drag it sees, which is correct for the chat surface but
 * wrong for anything inside it that scrolls or swipes sideways itself — a `ViewPager2`, a scrolling
 * `TabLayout`, the chat input's attachment strip, the inline call tile. Those views register
 * themselves here; the observer keeps their current window rectangles up to date and publishes the
 * whole set to whoever hosts the panels ([com.discord.widgets.home.WidgetHome]), which forwards it
 * to [OverlappingPanelsLayout.setChildGestureRegions].
 *
 * Rectangles are keyed by `View.getId()`, exactly as the reference does, so a view without an id
 * would collide with every other id-less view. Every registration site in the reference passes a
 * view with an id.
 *
 * Two things keep the rectangles fresh:
 *
 * * [View.OnLayoutChangeListener] — this class is the listener, so a re-layout re-measures.
 * * a per-view [ViewTreeObserver.OnScrollChangedListener] — a view can move without re-laying out
 *   when an ancestor scrolls, and a stale rectangle would hand gestures to the wrong owner.
 *
 * Access is main-thread only; there is no synchronisation, matching the reference.
 */
class PanelsChildGestureRegionObserver : View.OnLayoutChangeListener {

    /** Receives the full region set every time any registered view moves or resizes. */
    fun interface GestureRegionsListener {
        fun onGestureRegionsUpdate(gestureRegions: List<Rect>)
    }

    private val gestureRegions = LinkedHashMap<Int, Rect>()
    private val scrollChangedListeners = LinkedHashMap<Int, ViewTreeObserver.OnScrollChangedListener>()
    private val gestureRegionsListeners = LinkedHashSet<GestureRegionsListener>()

    /**
     * Starts tracking [view]. Registering the same view id twice is a no-op and is logged rather
     * than throwing, because the reference tolerates a screen that binds twice without unbinding.
     */
    @UiThread
    fun register(view: View) {
        if (scrollChangedListeners.containsKey(view.id)) {
            AppLog.w(TAG, "failed to register view with ID ${view.id}. already registered")
            return
        }
        view.addOnLayoutChangeListener(this)
        val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
            onLayoutChange(view, view.left, view.top, view.right, view.bottom, 0, 0, 0, 0)
        }
        view.viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
        scrollChangedListeners[view.id] = scrollChangedListener
    }

    /** Stops tracking [view] and republishes the remaining regions so the gap closes immediately. */
    @UiThread
    fun unregister(view: View) {
        scrollChangedListeners.remove(view.id)?.let(view.viewTreeObserver::removeOnScrollChangedListener)
        view.removeOnLayoutChangeListener(this)
        gestureRegions.remove(view.id)
        publish()
    }

    fun addGestureRegionsUpdateListener(listener: GestureRegionsListener) {
        gestureRegionsListeners += listener
    }

    fun removeGestureRegionsUpdateListener(listener: GestureRegionsListener) {
        gestureRegionsListeners -= listener
    }

    /** The regions known right now, for a listener that has just attached. */
    fun getCurrentGestureRegions(): List<Rect> = gestureRegions.values.toList()

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
        // A layout change can arrive after unregister() if the view is mid-teardown; the id check
        // is what stops a dead view from resurrecting its region.
        if (view.id !in scrollChangedListeners.keys) return
        val location = intArrayOf(0, 0)
        view.getLocationInWindow(location)
        val windowX = location[0]
        val windowY = location[1]
        // `right`/`bottom` are measured in the parent's space, so they are offsets from the window
        // position rather than window coordinates of their own.
        gestureRegions[view.id] = Rect(windowX, windowY, right + windowX, bottom + windowY)
        publish()
    }

    private fun publish() {
        val regions = gestureRegions.values.toList()
        gestureRegionsListeners.forEach { it.onGestureRegionsUpdate(regions) }
    }

    /**
     * Process-wide holder. The reference keeps the observer in a [WeakReference] so it dies with
     * the last screen that used it and a later screen gets a clean instance.
     */
    object Provider {
        private var instance: WeakReference<PanelsChildGestureRegionObserver> = WeakReference(null)

        @UiThread
        fun get(): PanelsChildGestureRegionObserver = instance.get() ?: PanelsChildGestureRegionObserver().also {
            instance = WeakReference(it)
        }
    }

    private companion object {
        private const val TAG = "PanelsChildGestureRegionObserver"
    }
}
