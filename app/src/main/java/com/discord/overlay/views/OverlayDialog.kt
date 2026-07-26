package com.discord.overlay.views

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.WindowManager

/**
 * A full-screen, dimming overlay window.
 *
 * Structurally this is still an [OverlayBubbleWrap] — it owns a `TYPE_APPLICATION_OVERLAY` window
 * and is managed by the same `OverlayManager` — but it behaves like a dialog rather than a bubble:
 *
 * * the window fills the screen and is pinned to the origin,
 * * `FLAG_DIM_BEHIND` with a 50% dim amount darkens whatever is behind it,
 * * dragging is disabled, so the bubble gesture handling in the superclass never engages,
 * * the press-state animator is cleared, because a full-screen surface must not scale on touch.
 *
 * A configuration change re-lays out the whole overlay window, so the dialog re-pins itself to the
 * origin rather than trying to preserve a position that no longer means anything.
 */
open class OverlayDialog(context: Context) : OverlayBubbleWrap(context) {

    init {
        windowLayoutParams.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            x = 0
            y = 0
            addFlag(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dimAmount = DIM_AMOUNT
        }
        stateListAnimator = null
    }

    /**
     * Always `false` — a dialog fills the screen, so there is nowhere to drag it to.
     *
     * The setter deliberately does not throw: the superclass's touch handling still tries to arm a
     * drag, and swallowing it with a log is what keeps that path harmless.
     */
    override var isMoving: Boolean
        get() = false
        set(value) {
            Log.w(javaClass.simpleName, "Move disabled: ignoring isMoving = $value")
        }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        windowLayoutParams.x = 0
        windowLayoutParams.y = 0
        windowManager.updateViewLayout(this, windowLayoutParams)
    }

    /** Adds a raw `WindowManager.LayoutParams` flag; the change takes effect on the next update. */
    fun setFlag(flag: Int) {
        windowLayoutParams.addFlag(flag)
    }

    private companion object {
        private const val DIM_AMOUNT = 0.5f
    }
}
