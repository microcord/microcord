package com.discord.views.guildtag

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import com.discord.R
import androidx.core.content.res.use
import com.discord.utilities.color.ColorCompat

/**
 * One square in the badge or colour grid.
 *
 * The border is always drawn and only changes colour when selected, so picking a cell cannot resize
 * its content box and reflow the grid. Built on a plain [GradientDrawable] rather than a
 * `MaterialButton`'s stroke, which depends on the button style resolving to a Material shape and
 * silently draws nothing when it does not.
 */
class GuildTagPickerCell(context: Context) : FrameLayout(context) {

    private val artwork = ImageView(context)
    private val border = GradientDrawable().apply {
        cornerRadius = dp(CORNER_DP).toFloat()
        setColor(ColorCompat.getThemedColor(context, R.attr.colorBackgroundSecondary))
    }

    init {
        background = border
        isClickable = true
        isFocusable = true
        foreground = context.obtainStyledAttributes(intArrayOf(R.attr.selectableItemBackground))
            .use { it.getDrawable(0) }
        addView(
            artwork,
            LayoutParams(dp(ART_DP), dp(ART_DP), Gravity.CENTER),
        )
        setSelectedState(false)
    }

    fun setArtwork(drawable: Drawable?) {
        artwork.setImageDrawable(drawable)
    }

    fun setSelectedState(selected: Boolean) {
        border.setStroke(
            dp(STROKE_DP),
            ColorCompat.getThemedColor(
                context,
                if (selected) R.attr.color_brand_500 else R.attr.colorBackgroundModifierAccent,
            ),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val CORNER_DP = 8
        const val STROKE_DP = 2
        const val ART_DP = 28
    }
}
