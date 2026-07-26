package com.discord.utilities.spans

import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import androidx.annotation.ColorInt

/**
 * Hides spoiler text until it is tapped.
 *
 * While hidden the *foreground* is painted the same colour as the background, so the glyphs are
 * still laid out and measured at their real width — the block does not resize when revealed, and
 * selecting or copying the text still yields the real characters. Revealing swaps to a lighter
 * background and stops overriding the text colour.
 *
 * [isRevealed] is mutable and read at draw time; a caller flips it and invalidates the view.
 */
class SpoilerSpan(
    @ColorInt private val hiddenColor: Int,
    @ColorInt private val revealedColor: Int,
    var isRevealed: Boolean = false,
) : BackgroundColorSpan(hiddenColor) {

    override fun getBackgroundColor(): Int = if (isRevealed) revealedColor else hiddenColor

    override fun updateDrawState(textPaint: TextPaint) {
        if (isRevealed) {
            textPaint.bgColor = revealedColor
        } else {
            textPaint.bgColor = hiddenColor
            textPaint.color = hiddenColor
        }
    }
}
