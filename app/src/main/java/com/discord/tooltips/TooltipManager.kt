package com.discord.tooltips

import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import com.discord.utilities.cache.SharedPreferencesProvider
import java.lang.ref.WeakReference
import rx.Observable

/**
 * Where a floating view is placed relative to its anchor.
 *
 * The reference ships this enum, and [FloatingViewManager], in a separate
 * `com.discord.floating_view_manager` module. That module has not been reconstructed yet, so both
 * live here next to their only consumer; move them once the module exists.
 */
enum class FloatingViewGravity {
    /** Floating view sits above the anchor. */
    TOP,

    /** Floating view is vertically centered on the anchor. */
    CENTER,

    /** Floating view sits below the anchor. */
    BOTTOM,
}

/**
 * Persistent record of which tooltips the user has explicitly acknowledged. An acknowledged
 * tooltip never shows again on any later cold start.
 *
 * Keys are the tooltip's [TooltipManager.Tooltip.cacheKey], stored as booleans in the default
 * shared preferences file.
 */
class AcknowledgedTooltipsCache(
    private val sharedPreferences: SharedPreferences = SharedPreferencesProvider.get(),
) {
    fun hasAcknowledgedTooltip(tooltipCacheKey: String): Boolean =
        sharedPreferences.getBoolean(tooltipCacheKey, false)

    fun ackTooltip(tooltipCacheKey: String) {
        sharedPreferences.edit().putBoolean(tooltipCacheKey, true).apply()
    }
}

/**
 * Attaches arbitrary views to the window of an anchor view, keeps them positioned while the
 * hierarchy moves, and removes them again.
 *
 * Floating views are keyed by [View.getId], so a caller re-showing the same view id replaces the
 * previous placement instead of stacking a second copy.
 */
class FloatingViewManager {

    private data class FloatingViewData(
        val floatingView: View,
        val ancestorViewGroup: ViewGroup,
        val ancestorPreDrawListener: ViewTreeObserver.OnPreDrawListener,
    )

    private val floatingViews = LinkedHashMap<Int, FloatingViewData>()

    /** Invoked with the view id after a floating view has been detached. */
    var onFloatingViewRemoved: ((Int) -> Unit)? = null

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

        // Stay invisible until both views have been laid out, otherwise the first frame is drawn
        // at the wrong position.
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
                    Log.e(
                        TAG,
                        "error while subscribing to componentPausedObservable for hiding floating view",
                        throwable,
                    )
                },
            )
    }

    @MainThread
    fun removeFloatingView(floatingViewId: Int) {
        val data = floatingViews[floatingViewId] ?: return
        floatingViews.remove(floatingViewId)
        data.ancestorViewGroup.viewTreeObserver.removeOnPreDrawListener(data.ancestorPreDrawListener)
        data.ancestorViewGroup.removeView(data.floatingView)
        onFloatingViewRemoved?.invoke(floatingViewId)
    }

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
     * Process-wide provider, held weakly so the manager dies with the last screen using it.
     */
    object Provider {
        private var instance: WeakReference<FloatingViewManager>? = null

        @MainThread
        fun get(): FloatingViewManager {
            instance?.get()?.let { return it }
            val floatingViewManager = FloatingViewManager()
            instance = WeakReference(floatingViewManager)
            return floatingViewManager
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

/**
 * Decides which tooltips may be shown, remembers which ones have already been shown in this cold
 * start, persists acknowledgements, and drives the floating view that renders them.
 *
 * Two independent gates exist:
 *
 * * **Acknowledgement** — persisted in [AcknowledgedTooltipsCache]; once a tooltip is acked it
 *   never shows again.
 * * **Cold-start quota** — at most [maxTooltipsPerColdStart] *distinct* tooltips are shown per
 *   process lifetime, so a session never drowns the user in coach marks. Re-showing a tooltip
 *   already shown in this cold start does not consume quota, and a caller may bypass the quota
 *   with `ignoreMaxTooltips`.
 *
 * A tooltip without a [Tooltip.cacheKey] is transient: neither gate applies to it.
 */
open class TooltipManager(
    private val acknowledgedTooltipsCache: AcknowledgedTooltipsCache,
    private val shownTooltipNames: MutableSet<String>,
    private val maxTooltipsPerColdStart: Int = 1,
    private val floatingViewManager: FloatingViewManager,
) {

    /**
     * Identifies a tooltip.
     *
     * @param cacheKey preference key holding the persisted acknowledgement. `null` means the
     *   tooltip is never persisted and is always allowed to show.
     * @param tooltipName stable name used for the per-cold-start quota and to look up the view
     *   currently rendering this tooltip.
     */
    open class Tooltip(
        val cacheKey: String? = null,
        val tooltipName: String,
    )

    /**
     * Maps a tooltip name to the id of the view currently rendering it, so [hideTooltip] can find
     * and detach it.
     */
    private var tooltipNameToTooltipViewIdMap: MutableMap<String, Int> = LinkedHashMap()

    init {
        floatingViewManager.onFloatingViewRemoved = { removedViewId ->
            tooltipNameToTooltipViewIdMap = tooltipNameToTooltipViewIdMap
                .filterValues { viewId -> viewId != removedViewId }
                .toMutableMap()
        }
    }

    /**
     * Whether [tooltip] may be shown right now.
     *
     * @param ignoreMaxTooltips skips the cold-start quota, but never the acknowledgement gate.
     */
    @MainThread
    fun canShowTooltip(tooltip: Tooltip, ignoreMaxTooltips: Boolean = false): Boolean {
        val cacheKey = tooltip.cacheKey ?: return true
        if (acknowledgedTooltipsCache.hasAcknowledgedTooltip(cacheKey)) return false
        val alreadyShownThisColdStart = shownTooltipNames.contains(tooltip.tooltipName)
        if (!alreadyShownThisColdStart &&
            !ignoreMaxTooltips &&
            shownTooltipNames.size >= maxTooltipsPerColdStart
        ) {
            return false
        }
        return true
    }

    /**
     * Records the user acknowledging [tooltip] and hides it. Acknowledgement is persisted, so the
     * tooltip will not show again on later cold starts.
     */
    @MainThread
    fun ackTooltip(tooltip: Tooltip) {
        hideTooltip(tooltip)
        val cacheKey = tooltip.cacheKey ?: return
        if (acknowledgedTooltipsCache.hasAcknowledgedTooltip(cacheKey)) return
        acknowledgedTooltipsCache.ackTooltip(cacheKey)
    }

    /** Detaches the view currently rendering [tooltip], if any. Safe to call when not showing. */
    @MainThread
    fun hideTooltip(tooltip: Tooltip) {
        val tooltipViewId = tooltipNameToTooltipViewIdMap[tooltip.tooltipName] ?: return
        floatingViewManager.removeFloatingView(tooltipViewId)
    }

    /**
     * Shows [tooltipView] anchored to [anchorView] if [canShowTooltip] allows it, replacing any
     * view already rendering the same tooltip.
     *
     * The tooltip is removed when [componentPausedObservable] emits, which callers wire to the
     * hosting fragment/view pause signal so a tooltip never outlives its screen.
     */
    @MainThread
    fun showTooltip(
        anchorView: View,
        tooltipView: View,
        tooltip: Tooltip,
        tooltipGravity: FloatingViewGravity,
        xOffset: Int = 0,
        yOffset: Int = 0,
        ignoreMaxTooltips: Boolean = false,
        componentPausedObservable: Observable<Unit>,
    ) {
        if (!canShowTooltip(tooltip, ignoreMaxTooltips)) return

        hideTooltip(tooltip)
        shownTooltipNames.add(tooltip.tooltipName)
        tooltipNameToTooltipViewIdMap[tooltip.tooltipName] = tooltipView.id

        floatingViewManager.addFloatingView(
            anchorView = anchorView,
            floatingView = tooltipView,
            floatingViewGravity = tooltipGravity,
            xOffset = xOffset,
            yOffset = yOffset,
            componentPausedObservable = componentPausedObservable,
        )
    }

    @VisibleForTesting
    internal fun getShownTooltipNames(): Set<String> = shownTooltipNames

    /**
     * Process-wide provider. The manager itself is held weakly so it dies with the last screen
     * using it, while the acknowledgement cache and the set of tooltips already shown in this cold
     * start outlive it — that is what makes the quota a cold-start quota rather than a
     * per-screen one.
     */
    object Provider {
        private var instance: WeakReference<TooltipManager>? = null
        private val acknowledgedTooltipsCache: AcknowledgedTooltipsCache by lazy {
            AcknowledgedTooltipsCache()
        }
        private val shownTooltipNames: MutableSet<String> by lazy { LinkedHashSet<String>() }

        @MainThread
        fun get(floatingViewManager: FloatingViewManager): TooltipManager {
            instance?.get()?.let { return it }
            val tooltipManager = TooltipManager(
                acknowledgedTooltipsCache = acknowledgedTooltipsCache,
                shownTooltipNames = shownTooltipNames,
                floatingViewManager = floatingViewManager,
            )
            instance = WeakReference(tooltipManager)
            return tooltipManager
        }

        @VisibleForTesting
        internal fun resetForTest() {
            instance = null
            shownTooltipNames.clear()
        }
    }
}
