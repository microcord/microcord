package com.discord.panels

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import com.discord.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

sealed class PanelState {
    data object Closed : PanelState()
    data object Opening : PanelState()
    data object Open : PanelState()
    data object Closing : PanelState()
}

open class OverlappingPanelsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * Hands the insets to every panel instead of stopping at the first that consumes them.
     *
     * All three panels set `fitsSystemWindows`, and each paints its own colour behind the system
     * bars. The default dispatch walks children in order and stops at the first consumer, so the
     * start panel would take the insets and the centre panel — the chat — would never be told to
     * inset itself, leaving the bars over whatever is behind the panels rather than over the chat.
     */
    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        for (index in 0 until childCount) {
            getChildAt(index).dispatchApplyWindowInsets(WindowInsets(insets))
        }
        return insets
    }

    enum class Panel { START, CENTER, END }
    enum class LockState { OPEN, CLOSE, UNLOCKED }
    fun interface PanelStateListener { fun onPanelStateChange(panelState: PanelState) }

    private var startPanel: View? = null
    private var centerPanel: View? = null
    private var endPanel: View? = null
    private var selectedPanel = Panel.CENTER
    private var startPanelState: PanelState = PanelState.Closed
    private var endPanelState: PanelState = PanelState.Closed
    private var startPanelLockState = LockState.UNLOCKED
    private var endPanelLockState = LockState.UNLOCKED
    private val startPanelStateListeners = arrayListOf<PanelStateListener>()
    private val endPanelStateListeners = arrayListOf<PanelStateListener>()
    private var childGestureRegions: List<Rect> = emptyList()
    private var useFullWidthForStartPanel = false
    /*
     * Sentinels, not zeroes. Confirmed in the reference's <init> bytecode, which loads
     * FloatCompanionObject.MIN_VALUE and MAX_VALUE into exactly these two fields.
     *
     * They record where the centre panel sits while each side panel is open, and updatePanelWidths
     * compares centerPanel.x against the *previous* value to decide whether an already-open panel
     * should be re-opened at its new width. centerPanel.x is 0f on the first layout, so initialising
     * these to 0f makes that comparison true every time: the start panel auto-opens, selectedPanel
     * becomes START, and WidgetHome answers the next back press with moveTaskToBack(true) — which
     * is indistinguishable from the app closing. That was the reported "back button closes the app".
     */
    private var startPanelOpenedCenterPanelX = Float.MIN_VALUE
    private var endPanelOpenedCenterPanelX = Float.MAX_VALUE
    private var centerPanelAnimationEndX = 0f
    private var centerPanelXAnimator: ValueAnimator? = null
    private var pendingUpdate: (() -> Unit)? = null
    private var centerPanelDiffX = 0f
    private var xFromInterceptActionDown = 0f
    private var yFromInterceptActionDown = 0f
    private var isScrollingHorizontally = false
    private var wasActionDownOnClosedCenterPanel = false
    private var isHomeSystemGesture = false
    private var swipeDirection: SwipeDirection? = null
    private var velocityTracker: VelocityTracker? = null
    private val isLeftToRight = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR
    private val scrollingSlopPx = resources.getDimension(R.dimen.overlapping_panels_scroll_slop)
    private val homeGestureFromBottomThreshold =
        resources.getDimension(R.dimen.overlapping_panels_home_gesture_from_bottom_threshold)
    private val minFlingPxPerSecond =
        resources.getDimension(R.dimen.overlapping_panels_min_fling_dp_per_second)
    private var nonFullScreenSidePanelWidth: Int

    private enum class SwipeDirection { LEFT, RIGHT }

    init {
        val displaySize = if (resources.configuration.orientation == 1) {
            resources.displayMetrics.widthPixels
        } else {
            resources.displayMetrics.heightPixels
        }
        nonFullScreenSidePanelWidth = (
            displaySize - resources.getDimension(R.dimen.overlapping_panels_margin_between_panels) -
                resources.getDimension(R.dimen.overlapping_panels_closed_center_panel_visible_width)
            ).toInt()
        context.obtainStyledAttributes(attrs, intArrayOf(R.attr.maxSidePanelNonFullScreenWidth)).run {
            try {
                nonFullScreenSidePanelWidth = min(
                    nonFullScreenSidePanelWidth,
                    getDimensionPixelSize(0, Int.MAX_VALUE),
                )
            } finally {
                recycle()
            }
        }
        isSaveEnabled = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (childCount == 3 && centerPanel == null) initPanels()
    }

    private fun initPanels() {
        startPanel = getChildAt(0)
        centerPanel = getChildAt(1)
        endPanel = getChildAt(2)
        startPanel!!.visibility = View.INVISIBLE
        centerPanel!!.visibility = View.VISIBLE
        endPanel!!.visibility = View.INVISIBLE
        startPanel!!.elevation = 0f
        centerPanel!!.elevation = 0f
        endPanel!!.elevation = 0f
        resetStartPanelWidth()
        resetEndPanelWidth()
        updatePanelWidths()
        pendingUpdate?.invoke()
        pendingUpdate = null
        startPanel!!.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) updatePanelWidths()
        }
        endPanel!!.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) updatePanelWidths()
        }
    }

    private fun resetStartPanelWidth() {
        startPanel?.layoutParams = startPanel?.layoutParams?.apply {
            width = if (useFullWidthForStartPanel) LayoutParams.MATCH_PARENT else nonFullScreenSidePanelWidth
        }
    }

    private fun resetEndPanelWidth() {
        endPanel?.layoutParams = endPanel?.layoutParams?.apply { width = nonFullScreenSidePanelWidth }
    }

    private fun updatePanelWidths() {
        val margin = resources.getDimension(R.dimen.overlapping_panels_margin_between_panels)
        val oldStart = startPanelOpenedCenterPanelX
        val oldEnd = endPanelOpenedCenterPanelX
        startPanelOpenedCenterPanelX = (startPanel!!.width + margin) * if (isLeftToRight) 1 else -1
        endPanelOpenedCenterPanelX = -(endPanel!!.width + margin) * if (isLeftToRight) 1 else -1
        when {
            centerPanel!!.x == oldStart || centerPanelAnimationEndX == oldStart -> openStartPanel()
            centerPanel!!.x == oldEnd || centerPanelAnimationEndX == oldEnd -> openEndPanel()
        }
    }

    fun openStartPanel() = openStartPanel(false)
    fun openEndPanel() = openEndPanel(false)
    fun closePanels() = closePanels(false)

    fun closePanelsImmediately() {
        if (centerPanel == null) {
            pendingUpdate = ::closePanelsImmediately
            return
        }
        centerPanelXAnimator?.cancel()
        centerPanelAnimationEndX = 0f
        updateCenterPanelX(0f)
    }

    private fun openStartPanel(fromGesture: Boolean) {
        if (centerPanel == null) {
            pendingUpdate = { openStartPanel(fromGesture) }
        } else if (startPanelLockState == LockState.OPEN) {
            updateCenterPanelX(startPanelOpenedCenterPanelX)
        } else {
            animateCenterPanel(startPanelOpenedCenterPanelX, fromGesture, 250L)
        }
    }

    private fun openEndPanel(fromGesture: Boolean) {
        if (centerPanel == null) pendingUpdate = { openEndPanel(fromGesture) }
        else animateCenterPanel(endPanelOpenedCenterPanelX, fromGesture, 250L)
    }

    private fun closePanels(fromGesture: Boolean) {
        if (centerPanel == null) pendingUpdate = { closePanels(fromGesture) }
        else animateCenterPanel(0f, fromGesture, 200L)
    }

    private fun animateCenterPanel(target: Float, fromGesture: Boolean, duration: Long) {
        val center = centerPanel ?: return
        centerPanelXAnimator?.cancel()
        centerPanelAnimationEndX = normalizedX(target)
        centerPanelXAnimator = ValueAnimator.ofFloat(center.x, centerPanelAnimationEndX).apply {
            interpolator = if (fromGesture) LinearOutSlowInInterpolator() else FastOutSlowInInterpolator()
            this.duration = duration
            addUpdateListener { updateCenterPanelX(it.animatedValue as Float) }
            start()
        }
    }

    private fun updateCenterPanelX(x: Float) {
        val center = centerPanel ?: return
        val oldX = center.x
        center.x = x
        handleCenterPanelX(oldX, x)
    }

    private fun handleCenterPanelX(oldX: Float, newX: Float) {
        val center = centerPanel ?: return
        startPanel!!.visibility = if ((isLeftToRight && newX > 0) || (!isLeftToRight && newX < 0)) View.VISIBLE else View.INVISIBLE
        endPanel!!.visibility = if ((isLeftToRight && newX < 0) || (!isLeftToRight && newX > 0)) View.VISIBLE else View.INVISIBLE
        selectedPanel = when (newX) {
            0f -> Panel.CENTER
            startPanelOpenedCenterPanelX -> Panel.START
            endPanelOpenedCenterPanelX -> Panel.END
            else -> selectedPanel
        }
        val restingSidePanel = newX == startPanelOpenedCenterPanelX || newX == endPanelOpenedCenterPanelX
        center.alpha = if (restingSidePanel) 0.5f else 1f
        center.importantForAccessibility =
            if (restingSidePanel) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        center.elevation = if (newX == 0f || restingSidePanel) 0f
        else resources.getDimension(R.dimen.overlapping_panels_center_panel_non_resting_elevation)
        startPanelState = panelState(oldX, newX, startPanelOpenedCenterPanelX, true)
        endPanelState = panelState(oldX, newX, endPanelOpenedCenterPanelX, false)
        startPanelStateListeners.forEach { it.onPanelStateChange(startPanelState) }
        endPanelStateListeners.forEach { it.onPanelStateChange(endPanelState) }
    }

    private fun panelState(oldX: Float, newX: Float, openedX: Float, start: Boolean): PanelState {
        val towardPanel = if (start == isLeftToRight) newX > oldX else newX < oldX
        return when {
            newX == openedX -> PanelState.Open
            newX == 0f || (start == isLeftToRight && newX < 0) || (start != isLeftToRight && newX > 0) -> PanelState.Closed
            towardPanel -> PanelState.Opening
            else -> PanelState.Closing
        }
    }

    fun handleStartPanelState(state: PanelState) {
        if (state == PanelState.Open && startPanelState != PanelState.Open) openStartPanel()
        else if (state == PanelState.Closed && startPanelState == PanelState.Open) closePanels()
        startPanelState = state
    }

    fun handleEndPanelState(state: PanelState) {
        if (state == PanelState.Open && endPanelState != PanelState.Open) openEndPanel()
        else if (state == PanelState.Closed && endPanelState == PanelState.Open) closePanels()
        endPanelState = state
    }

    fun setStartPanelLockState(lockState: LockState) {
        startPanelLockState = lockState
        if (lockState == LockState.OPEN) openStartPanel()
    }

    fun setEndPanelLockState(lockState: LockState) {
        endPanelLockState = lockState
        if (lockState == LockState.OPEN) openEndPanel()
    }

    fun setStartPanelUseFullPortraitWidth(useFullWidth: Boolean) {
        useFullWidthForStartPanel = useFullWidth
        resetStartPanelWidth()
    }

    fun getSelectedPanel(): Panel = selectedPanel
    fun setChildGestureRegions(regions: List<Rect>) { childGestureRegions = regions }

    /**
     * Asked, with the gesture's down event, whether a leftward swipe on the center panel belongs to
     * a child instead of opening the end panel. Swipe to reply claims swipes that start on a message.
     */
    var childClaimsLeftSwipe: ((MotionEvent) -> Boolean)? = null
    private var leftSwipeClaimed = false
    fun registerStartPanelStateListeners(vararg listeners: PanelStateListener) { startPanelStateListeners += listeners }
    fun registerEndPanelStateListeners(vararg listeners: PanelStateListener) { endPanelStateListeners += listeners }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isScrollingHorizontally = false
                wasActionDownOnClosedCenterPanel = touchingCenterWhilePanelOpen(event)
                centerPanelDiffX = (centerPanel?.x ?: 0f) - event.rawX
                xFromInterceptActionDown = event.x
                yFromInterceptActionDown = event.y
                leftSwipeClaimed = selectedPanel == Panel.CENTER && childClaimsLeftSwipe?.invoke(event) == true
                isHomeSystemGesture = Build.VERSION.SDK_INT >= 29 &&
                    abs(event.y - resources.displayMetrics.heightPixels) < homeGestureFromBottomThreshold
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                return wasActionDownOnClosedCenterPanel
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScrollingHorizontally) return true
                val dx = event.x - xFromInterceptActionDown
                val dy = event.y - yFromInterceptActionDown
                if (abs(dx) > scrollingSlopPx && abs(dx) > abs(dy) && !touchingChildGestureRegion(event) &&
                    !(dx < 0 && leftSwipeClaimed)
                ) {
                    isScrollingHorizontally = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                return isScrollingHorizontally || wasActionDownOnClosedCenterPanel
            }
        }
        return wasActionDownOnClosedCenterPanel
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isHomeSystemGesture) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (touchingChildGestureRegion(event)) return false
                val dx = event.x - xFromInterceptActionDown
                if (abs(dx) > scrollingSlopPx && swipeDirection == null) {
                    swipeDirection = if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                }
                velocityTracker?.addMovement(event)
                val target = normalizedX(event.rawX + centerPanelDiffX)
                if (abs(target - (centerPanel?.x ?: target)) > resources.displayMetrics.density ||
                    target == 0f || target == startPanelOpenedCenterPanelX || target == endPanelOpenedCenterPanelX) {
                    updateCenterPanelX(target)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tappedCenter = wasActionDownOnClosedCenterPanel &&
                    abs(event.x - xFromInterceptActionDown) < scrollingSlopPx && !isScrollingHorizontally
                if (tappedCenter) closePanels() else {
                    velocityTracker?.addMovement(event)
                    snapOpenOrClose(event)
                }
                wasActionDownOnClosedCenterPanel = false
                isScrollingHorizontally = false
                swipeDirection = null
            }
        }
        return true
    }

    private fun normalizedX(x: Float): Float {
        if (startPanelLockState == LockState.OPEN) return startPanelOpenedCenterPanelX
        if (endPanelLockState == LockState.OPEN) return endPanelOpenedCenterPanelX
        val leftBound = if (leftPanelLockState() == LockState.CLOSE ||
            (selectedPanel == Panel.CENTER && swipeDirection == SwipeDirection.LEFT)) 0f
        else max(startPanelOpenedCenterPanelX, endPanelOpenedCenterPanelX)
        val rightBound = if (rightPanelLockState() == LockState.CLOSE ||
            (selectedPanel == Panel.CENTER && swipeDirection == SwipeDirection.RIGHT)) 0f
        else min(startPanelOpenedCenterPanelX, endPanelOpenedCenterPanelX)
        return x.coerceIn(rightBound, leftBound)
    }

    private fun snapOpenOrClose(event: MotionEvent) {
        val target = event.rawX + centerPanelDiffX
        velocityTracker?.computeCurrentVelocity(1000)
        val velocity = velocityTracker?.xVelocity ?: 0f
        if (abs(velocity) > minFlingPxPerSecond) {
            val towardStart = if (isLeftToRight) velocity > 0 else velocity < 0
            if (towardStart) {
                if (selectedPanel == Panel.END) closePanels(true) else if (selectedPanel == Panel.CENTER) openStartPanel(true)
            } else {
                if (selectedPanel == Panel.START) closePanels(true) else if (selectedPanel == Panel.CENTER) openEndPanel(true)
            }
            return
        }
        when {
            target > max(startPanelOpenedCenterPanelX, endPanelOpenedCenterPanelX) / 2 ->
                openPanel(if (isLeftToRight) Panel.START else Panel.END)
            target < min(startPanelOpenedCenterPanelX, endPanelOpenedCenterPanelX) / 2 ->
                openPanel(if (isLeftToRight) Panel.END else Panel.START)
            else -> closePanels()
        }
    }

    private fun openPanel(panel: Panel) = when (panel) {
        Panel.START -> openStartPanel()
        Panel.CENTER -> closePanels()
        Panel.END -> openEndPanel()
    }

    private fun leftPanelLockState() = if (isLeftToRight) startPanelLockState else endPanelLockState
    private fun rightPanelLockState() = if (isLeftToRight) endPanelLockState else startPanelLockState

    private fun touchingChildGestureRegion(event: MotionEvent) = childGestureRegions.any {
        event.rawX >= it.left && event.rawX <= it.right && event.rawY >= it.top && event.rawY <= it.bottom
    }

    private fun touchingCenterWhilePanelOpen(event: MotionEvent): Boolean {
        val center = centerPanel ?: return false
        val leftOpenX = max(startPanelOpenedCenterPanelX, endPanelOpenedCenterPanelX)
        val rightOpenX = min(startPanelOpenedCenterPanelX, endPanelOpenedCenterPanelX)
        return (center.x == leftOpenX && event.x > leftOpenX) ||
            (center.x == rightOpenX && event.x < center.width + rightOpenX)
    }

    override fun onSaveInstanceState(): Parcelable = SavedState(super.onSaveInstanceState()).also {
        it.selectedPanel = selectedPanel.ordinal
        it.startLockState = startPanelLockState.ordinal
        it.endLockState = endPanelLockState.ordinal
        it.fullWidthStartPanel = useFullWidthForStartPanel
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        startPanelLockState = LockState.entries[state.startLockState]
        endPanelLockState = LockState.entries[state.endLockState]
        useFullWidthForStartPanel = state.fullWidthStartPanel
        // Restore the resting panel before layout runs, not only once the pending update applies
        // the matching x offset. Back press is answered from these three fields, and a back press
        // can land between the restore and the first layout pass.
        val restored = Panel.entries[state.selectedPanel]
        selectedPanel = restored
        startPanelState = if (restored == Panel.START) PanelState.Open else PanelState.Closed
        endPanelState = if (restored == Panel.END) PanelState.Open else PanelState.Closed
        pendingUpdate = {
            resetStartPanelWidth()
            updatePanelWidths()
            updateCenterPanelX(
                when (restored) {
                    Panel.START -> startPanelOpenedCenterPanelX
                    Panel.CENTER -> 0f
                    Panel.END -> endPanelOpenedCenterPanelX
                },
            )
        }
    }

    private class SavedState : BaseSavedState {
        var selectedPanel = 0
        var startLockState = 0
        var endLockState = 0
        var fullWidthStartPanel = false

        constructor(superState: Parcelable?) : super(superState)
        constructor(source: Parcel) : super(source) {
            selectedPanel = source.readInt()
            startLockState = source.readInt()
            endLockState = source.readInt()
            fullWidthStartPanel = source.readInt() != 0
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(selectedPanel)
            out.writeInt(startLockState)
            out.writeInt(endLockState)
            out.writeInt(if (fullWidthStartPanel) 1 else 0)
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            override fun createFromParcel(source: Parcel) = SavedState(source)
            override fun newArray(size: Int) = arrayOfNulls<SavedState>(size)
        }
    }
}
