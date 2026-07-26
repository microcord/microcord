package com.discord.utilities.spans

import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import androidx.annotation.ColorInt

/**
 * Recolours a run of text.
 *
 * A `MetricAffectingSpan` rather than the obvious `ForegroundColorSpan` because it also sets the
 * colour during the *measure* pass. That is what makes it survive being applied inside a
 * `ReplacementSpan` such as [SimpleRoundedBackgroundSpan], which measures the text itself with the
 * paint it is handed; a `CharacterStyle` would never be consulted there and the run would come out
 * in the base text colour.
 *
 * Reference location: `utilities/textprocessing/FontColorSpan`. Kept here with the rest of the
 * spans so `utilities/textprocessing` stays the parser and this stays the drawing layer.
 */
class FontColorSpan(@ColorInt private val colorInt: Int) : MetricAffectingSpan() {

    override fun updateDrawState(textPaint: TextPaint?) {
        textPaint?.color = colorInt
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.color = colorInt
    }
}
