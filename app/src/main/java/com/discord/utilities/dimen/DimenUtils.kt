@file:JvmName("DimenUtils")

package com.discord.utilities.dimen

import android.content.res.Resources

/**
 * Density of the *system* resources, not of any particular `Context`.
 *
 * Read once at class-init. This is what makes [dpToPixels] callable from a data class, a store, or
 * a companion object with no `Context` in scope. It also means a per-`Context` display override —
 * an activity forced into a different density, or a display with a different DPI — is not
 * reflected; the reference has the same limitation and every call site here is chrome sizing that
 * follows the default display.
 */
private val DENSITY: Float = Resources.getSystem().displayMetrics.density

/**
 * Density-independent pixels to physical pixels, truncated.
 *
 * Truncation rather than rounding is deliberate parity: `dpToPixels(1)` on a 1.5x display is 1px,
 * not 2px, so hairline dividers stay hairlines.
 */
fun dpToPixels(densityPixels: Int): Int = (densityPixels * DENSITY).toInt()

fun dpToPixels(densityPixels: Float): Int = (densityPixels * DENSITY).toInt()

/** Physical pixels back to density-independent pixels, truncated. */
fun pixelsToDp(pixels: Int): Int = (pixels / DENSITY).toInt()
