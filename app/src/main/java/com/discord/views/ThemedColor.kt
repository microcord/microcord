package com.discord.views

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

/**
 * Resolves a colour from a theme attribute.
 *
 * A theme attribute resolves either to a literal colour, where [TypedValue.data]
 * holds the ARGB value, or to a colour / colour-state-list resource, where
 * [TypedValue.data] is a resource identifier or a string-pool index and using it
 * as a colour produces an arbitrary wrong tint. Only the colour-typed case may
 * read `data` directly.
 */
internal object ThemedColor {
    @ColorInt
    fun resolve(context: Context, @AttrRes attribute: Int, @ColorInt fallback: Int = 0): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return resolve(value.type, value.data, value.resourceId, fallback) {
            ContextCompat.getColor(context, it)
        }
    }

    @ColorInt
    fun resolve(
        type: Int,
        data: Int,
        resourceId: Int,
        @ColorInt fallback: Int,
        colorForResource: (Int) -> Int,
    ): Int = when {
        type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> data
        resourceId != 0 -> colorForResource(resourceId)
        else -> fallback
    }
}
