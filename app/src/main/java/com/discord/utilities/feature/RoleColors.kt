package com.discord.utilities.feature

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.annotation.ColorInt
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.dimen.dpToPixels

/** Desktop's "Role Colours" appearance choice. */
enum class RoleColorMode { NAMES, DOT, NONE }

object RoleColors {
    private const val DOT_SIZE_DP = 8
    private const val DOT_PADDING_DP = 4

    fun mode(): RoleColorMode =
        ClientSettings.roleColorMode.get(SharedPreferencesProvider.get())
            ?.let { runCatching { RoleColorMode.valueOf(it) }.getOrNull() }
            ?: RoleColorMode.NAMES

    fun setMode(mode: RoleColorMode) {
        ClientSettings.roleColorMode.set(SharedPreferencesProvider.get(), mode.name)
    }

    /**
     * Where the dot's centre must sit relative to the centre of the line box the TextView centres
     * compound drawables in. Discord's fonts carry a lot of space above the capitals, so the line
     * box centre sits visibly below the letters; the cap height's middle is what the eye aligns to.
     */
    private fun capCenterOffset(name: TextView): Int {
        val paint = name.paint
        val metrics = paint.fontMetricsInt
        val lineTop = if (name.includeFontPadding) metrics.top else metrics.ascent
        val lineBottom = if (name.includeFontPadding) metrics.bottom else metrics.descent
        val capBounds = Rect().also { paint.getTextBounds("H", 0, 1, it) }
        return (capBounds.top + capBounds.bottom) / 2 - (lineTop + lineBottom) / 2
    }

    /** Draws a dot in [roleColor] after [name]'s text in [RoleColorMode.DOT], and clears it otherwise. */
    fun applyDot(name: TextView, @ColorInt roleColor: Int?) {
        val color = roleColor?.takeUnless { it == 0 || it == Color.BLACK }
            ?.takeIf { mode() == RoleColorMode.DOT }
        val dot = color?.let {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(it or Color.BLACK)
                val size = dpToPixels(DOT_SIZE_DP)
                // TextView already centres the drawable by its height; the bounds only shift it.
                val top = capCenterOffset(name)
                setBounds(0, top, size, top + size)
            }
        }
        val current = name.compoundDrawablesRelative
        name.compoundDrawablePadding = if (dot == null) 0 else dpToPixels(DOT_PADDING_DP)
        name.setCompoundDrawablesRelative(current[0], current[1], dot, current[3])
    }
}
