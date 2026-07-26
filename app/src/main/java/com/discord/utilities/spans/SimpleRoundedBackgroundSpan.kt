package com.discord.utilities.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.ColorInt
import com.discord.utilities.string.transformOrEmpty
import kotlin.math.roundToInt

/**
 * Draws a run of text as a rounded "chip" — the pill behind a mention, an AutoMod channel
 * reference, or a slash-command name.
 *
 * This is a `ReplacementSpan`, so it owns both measurement and drawing of its run. That is the only
 * way to make the chip's horizontal padding real: the extra width has to be reported from
 * [getSize] or the following text overlaps the pill.
 *
 * **It is applied per line, not once per chip.** [startIndex] and [endIndex] are the chip's
 * absolute bounds in the full text; each drawn segment compares its own `start`/`end` against them
 * to decide which edges it owns. Only the leading segment gets left rounding and the leading
 * margin, only the trailing segment gets right rounding — so a chip broken across a line wrap
 * renders as one continuous shape with square inner ends rather than two separate pills.
 *
 * @param edgeHorizontalPadding space inside the pill, between its edge and the glyphs.
 * @param edgeHorizontalMargin space outside the pill, separating it from adjacent text.
 * @param textColor overrides the paint's colour for the chip's own text; `null` inherits.
 * @param isTrimEnabled treats a chip whose last character is whitespace as ending one character
 *   early, so the trailing space sits outside the pill instead of widening it.
 * @param transformSpannedText rewrites the drawn label — used to prefix a `#` or `@` sigil that is
 *   not present in the underlying text.
 * @param topOffset nudges the baseline, for chips whose background is taller than the line.
 */
open class SimpleRoundedBackgroundSpan(
    private val startIndex: Int,
    private val endIndex: Int,
    private val edgeHorizontalPadding: Int,
    private val edgeHorizontalMargin: Int,
    @ColorInt private val backgroundColor: Int,
    private val cornerRadius: Float,
    @ColorInt private val textColor: Int? = null,
    private val isTrimEnabled: Boolean = true,
    private val transformSpannedText: ((String?) -> String?)? = null,
    private val topOffset: Int = 0,
) : android.text.style.ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val label = text?.subSequence(start, end)?.toString().transformOrEmpty(transformSpannedText)
        var size = paint.measureText(label).roundToInt()
        if (start == startIndex) size += edgeHorizontalMargin + edgeHorizontalPadding
        if (isAtEndEdge(end, text)) size += edgeHorizontalMargin
        return size + edgeHorizontalPadding
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val label = text?.subSequence(start, end)?.toString()
            .transformOrEmpty(transformSpannedText)
            .takeIf { it.isNotBlank() }
            ?: return

        var left = x
        val textX: Float
        // Total horizontal padding this segment contributes to the pill's width.
        val horizontalPadding: Int
        when {
            // Leading segment: push the whole pill right by the margin, then pad both sides.
            start == startIndex -> {
                left += edgeHorizontalMargin
                textX = left + edgeHorizontalPadding
                horizontalPadding = edgeHorizontalPadding * 2
            }
            // A continuation segment that starts a wrapped line (x == 0 on a line below the
            // first): it opens a fresh pill fragment, so it pads like a leading segment.
            x == 0f && top != 0 -> {
                textX = left + edgeHorizontalPadding
                horizontalPadding = edgeHorizontalPadding * 2
            }
            // Mid-run segment: no leading pad, the glyphs continue where they are.
            else -> {
                textX = left
                horizontalPadding = edgeHorizontalPadding
            }
        }

        val rect = RectF(
            left,
            top.toFloat(),
            paint.measureText(label) + left + horizontalPadding,
            bottom.toFloat(),
        )
        val path = Path().apply {
            addRoundRect(rect, calculateCornerRadius(start, end, text), Path.Direction.CW)
        }
        canvas.drawPath(path, Paint(paint).apply { color = backgroundColor })

        textColor?.let { paint.color = it }
        canvas.drawText(label, textX, (y + topOffset).toFloat(), paint)
    }

    /**
     * Corner radii in `[tl, tl, tr, tr, br, br, bl, bl]` order: rounded only on the edges this
     * segment actually owns.
     */
    private fun calculateCornerRadius(start: Int, end: Int, text: CharSequence?): FloatArray {
        val left = if (start == startIndex) cornerRadius else 0f
        val right = if (isAtEndEdge(end, text)) cornerRadius else 0f
        return floatArrayOf(left, left, right, right, right, right, left, left)
    }

    /**
     * Whether this segment closes the chip — either it truly ends at [endIndex], or trimming is on
     * and the one remaining character is whitespace that belongs outside the pill.
     */
    private fun isAtEndEdge(end: Int, text: CharSequence?): Boolean {
        if (end == endIndex) return true
        if (isTrimEnabled && endIndex - end == 1 && text != null) {
            val next = text.getOrNull(end)
            if (next != null && next.isWhitespace()) return true
        }
        return false
    }
}
