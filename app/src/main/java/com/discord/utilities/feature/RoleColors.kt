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

/**
 * [color] moved just far enough from [background] to read, or unchanged when it already does.
 *
 * Distance is the sum of the per-channel differences, and anything under [minDistance] is too close.
 * On a dark background the colour's lift above it is scaled up until the sum reaches [minDistance],
 * keeping its hue: on black, `#282800` becomes `#303000`. A colour with no lift at all (the
 * background itself, or darker) is lifted evenly, so black on black becomes `#202020`. A light
 * background mirrors this towards black. Alpha is kept.
 */
@ColorInt
internal fun readableNameColor(@ColorInt color: Int, @ColorInt background: Int, minDistance: Int = 0x60): Int {
    fun channel(value: Int, shift: Int) = (value shr shift) and 0xFF
    val shifts = intArrayOf(16, 8, 0)
    val fg = IntArray(3) { channel(color, shifts[it]) }
    val bg = IntArray(3) { channel(background, shifts[it]) }
    if ((0 until 3).sumOf { kotlin.math.abs(fg[it] - bg[it]) } >= minDistance) return color

    val dark = 299 * bg[0] + 587 * bg[1] + 114 * bg[2] < 128_000
    val direction = if (dark) 1 else -1
    // How far each channel already sits from the background in the direction it is being pushed.
    val lift = IntArray(3) { maxOf(0, (fg[it] - bg[it]) * direction) }
    val total = lift.sum()
    val moved = IntArray(3) {
        val offset = if (total == 0) minDistance / 3f else lift[it] * minDistance.toFloat() / total
        (bg[it] + direction * kotlin.math.round(offset).toInt()).coerceIn(0, 0xFF)
    }
    return (color and 0xFF000000.toInt()) or (moved[0] shl 16) or (moved[1] shl 8) or moved[2]
}

object RoleColors {
    private const val DOT_SIZE_DP = 8
    private const val DOT_PADDING_DP = 4

    /**
     * The chat background of the most recently built activity theme, custom themes included. Names
     * are kept readable against it; a see-through background (a wallpaper theme) gives no answer.
     */
    @Volatile
    var nameBackground: Int? = null

    /** [roleColor] as a name should draw it: shifted off the background when the feature is on. */
    @ColorInt
    fun forName(@ColorInt roleColor: Int): Int {
        if (!FeatureFlags.readableRoleColorsEnabled()) return roleColor
        val background = nameBackground?.takeIf { Color.alpha(it) == 0xFF } ?: return roleColor
        return readableNameColor(roleColor, background)
    }

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
