package com.discord.tooltips

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.MainThread
import com.discord.R
import rx.Observable

/**
 * Inflates and shows the standard text tooltip bubble.
 *
 * Screens that only need "a bubble with a tail pointing at this view" use this instead of building
 * their own floating view. Tapping the bubble acknowledges the tooltip, so it never reappears.
 *
 * Default tooltips always bypass the per-cold-start quota — they are shown in response to a
 * specific, already gated interaction, and the caller decides when they are relevant.
 */
class DefaultTooltipCreator(private val tooltipManager: TooltipManager) {

    @MainThread
    fun show(
        anchorView: View,
        tooltipText: CharSequence,
        tooltip: TooltipManager.Tooltip,
        tooltipGravity: FloatingViewGravity,
        xOffset: Int = 0,
        yOffset: Int = 0,
        componentPausedObservable: Observable<Unit>,
    ) {
        if (!tooltipManager.canShowTooltip(tooltip, ignoreMaxTooltips = true)) return

        val tooltipView = inflateTooltipView(anchorView, tooltipText, tooltipGravity)
        tooltipView.setOnClickListener {
            tooltipManager.ackTooltip(tooltip)
            tooltipManager.hideTooltip(tooltip)
        }

        tooltipManager.showTooltip(
            anchorView = anchorView,
            tooltipView = tooltipView,
            tooltip = tooltip,
            tooltipGravity = tooltipGravity,
            xOffset = xOffset,
            yOffset = yOffset,
            ignoreMaxTooltips = true,
            componentPausedObservable = componentPausedObservable,
        )
    }

    /**
     * A tooltip anchored above its target needs its tail on the bottom edge and vice versa, so the
     * gravity selects the layout. `CENTER` reuses the bottom-tail bubble.
     */
    private fun inflateTooltipView(
        anchorView: View,
        tooltipText: CharSequence,
        tooltipGravity: FloatingViewGravity,
    ): View {
        val layoutRes = when (tooltipGravity) {
            FloatingViewGravity.TOP -> R.layout.default_tooltip_view_top
            FloatingViewGravity.CENTER, FloatingViewGravity.BOTTOM ->
                R.layout.default_tooltip_view_bottom
        }
        val root = anchorView.rootView as ViewGroup
        val tooltipView = LayoutInflater.from(anchorView.context).inflate(layoutRes, root, false)
        val textView = requireNotNull(tooltipView.findViewById<TextView>(R.id.default_tooltip_text))
        textView.text = tooltipText
        return tooltipView
    }
}
