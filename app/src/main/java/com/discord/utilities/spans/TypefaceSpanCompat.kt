package com.discord.utilities.spans

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan

/**
 * Applies a concrete [Typeface] to a run of text.
 *
 * The platform's `TypefaceSpan` only takes a *family name* before API 28, so a font loaded from
 * resources cannot be applied through it at all. This span takes the object directly.
 *
 * It also preserves style the requested typeface cannot supply: if the surrounding text is bold or
 * italic and the new typeface has no such face, the paint is asked to fake it (synthetic bold /
 * skew) instead of silently dropping the emphasis — which is what makes `**bold `code`**` still
 * read as bold.
 */
class TypefaceSpanCompat(private val typeface: Typeface) : MetricAffectingSpan() {

    override fun updateDrawState(drawState: TextPaint) = apply(drawState)

    override fun updateMeasureState(paint: TextPaint) = apply(paint)

    private fun apply(paint: Paint) {
        val existingStyle = paint.typeface?.style ?: 0
        // Style bits the surrounding text wants that the target typeface cannot provide.
        val fake = existingStyle and typeface.style.inv()
        if (fake and Typeface.BOLD != 0) paint.isFakeBoldText = true
        if (fake and Typeface.ITALIC != 0) paint.textSkewX = ITALIC_SKEW
        paint.typeface = typeface
    }

    /** Monospace by family name — the code-span case, where no font object is involved. */
    class Monospace : TypefaceSpan("monospace")

    private companion object {
        const val ITALIC_SKEW = -0.25f
    }
}
