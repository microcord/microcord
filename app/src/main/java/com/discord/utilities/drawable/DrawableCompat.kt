@file:JvmName("DrawableCompat")

package com.discord.utilities.drawable

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import com.discord.utilities.color.setTint

/**
 * Theme-aware drawable lookup.
 *
 * Discord themes swap whole *drawables*, not just colours — the light and dark variants of a badge
 * or an empty-state illustration are different assets, selected by a theme attribute. That is what
 * [getThemedDrawableRes] resolves, and it is why tinting a single asset is not a substitute.
 *
 * Note this is `com.discord.utilities.drawable.DrawableCompat`, not AndroidX's class of the same
 * name. Import both in one file only with an alias.
 */

/**
 * Resolves the drawable resource a theme attribute points at.
 *
 * @param defaultResId returned when the theme does not define the attribute — pass a real drawable
 *   when the caller cannot handle `0`.
 */
@DrawableRes
@JvmOverloads
fun Context.getThemedDrawableRes(@AttrRes attrResId: Int, @DrawableRes defaultResId: Int = 0): Int {
    val typedArray = obtainStyledAttributes(intArrayOf(attrResId))
    try {
        return typedArray.getResourceId(0, defaultResId)
    } finally {
        typedArray.recycle()
    }
}

@DrawableRes
@JvmOverloads
fun View.getThemedDrawableRes(@AttrRes attrResId: Int, @DrawableRes defaultResId: Int = 0): Int =
    context.getThemedDrawableRes(attrResId, defaultResId)

/**
 * Loads a drawable and tints it.
 *
 * Goes through `AppCompatResources` so vector drawables and `<selector>`s with theme references
 * still load on API 21.
 */
@JvmOverloads
fun Context.getDrawable(
    @DrawableRes resId: Int,
    @ColorInt tint: Int,
    isGlobalTint: Boolean = false,
): Drawable? = AppCompatResources.getDrawable(this, resId)?.also { it.setTint(tint, isGlobalTint) }

/**
 * Sets compound drawables in the *relative* (start/end) form so they mirror under RTL, then
 * re-applies the view's existing compound tint.
 *
 * The re-apply is not redundant: setting new compound drawables drops the tint list that was
 * applied to the previous ones, so a themed tint declared in XML would be lost on the first
 * programmatic swap.
 */
fun TextView.setCompoundDrawablesCompat(
    @DrawableRes start: Int = 0,
    @DrawableRes top: Int = 0,
    @DrawableRes end: Int = 0,
    @DrawableRes bottom: Int = 0,
) {
    TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(this, start, top, end, bottom)
    applyCompoundDrawablesTint()
}

fun TextView.setCompoundDrawablesCompat(
    start: Drawable? = null,
    top: Drawable? = null,
    end: Drawable? = null,
    bottom: Drawable? = null,
) {
    TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(this, start, top, end, bottom)
    applyCompoundDrawablesTint()
}

private fun TextView.applyCompoundDrawablesTint() {
    TextViewCompat.getCompoundDrawableTintList(this)?.let {
        TextViewCompat.setCompoundDrawableTintList(this, it)
    }
}
