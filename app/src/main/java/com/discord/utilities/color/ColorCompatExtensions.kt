@file:JvmName("ColorCompatKt")

package com.discord.utilities.color

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment

@ColorInt
fun Fragment.getColor(@ColorRes colorResourceId: Int): Int =
    ColorCompat.getColor(this, colorResourceId)

@ColorInt
fun Fragment.getThemedColor(@AttrRes attributeResourceId: Int): Int =
    ColorCompat.getThemedColor(this, attributeResourceId)

/** Tints every compound drawable on the text view, leaving the text colour alone. */
fun TextView.setDrawableColor(@ColorInt color: Int) {
    compoundDrawables.filterNotNull().forEach {
        it.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }
}

/**
 * Tints a drawable.
 *
 * Pass `isGlobalTint = true` only when the tint is *meant* to affect every user of the shared
 * constant state — otherwise the drawable is mutated first, which is what stops one recycled row's
 * tint bleeding into every other row sharing the same cached drawable.
 */
fun Drawable.setTint(@ColorInt tint: Int, isGlobalTint: Boolean = false) {
    if (!isGlobalTint) mutate()
    DrawableCompat.setTint(this, tint)
}

fun ImageView.tintWithColor(@ColorInt color: Int) {
    imageTintList = ColorStateList.valueOf(color)
}

fun ImageView.tintWithColorResource(@ColorRes colorResId: Int) =
    tintWithColor(ColorCompat.getColor(this, colorResId))
