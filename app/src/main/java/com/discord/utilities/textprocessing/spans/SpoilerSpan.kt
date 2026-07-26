package com.discord.utilities.textprocessing.spans

import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import androidx.annotation.ColorInt

/**
 * Hides spoiler content by painting the foreground in the same colour as the background until the
 * spoiler is revealed, at which point only the (lighter) revealed background remains.
 */
class SpoilerSpan(
    @ColorInt private val hiddenColor: Int,
    @ColorInt private val revealedColor: Int,
    var isRevealed: Boolean,
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
