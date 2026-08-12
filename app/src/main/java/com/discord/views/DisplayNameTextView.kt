package com.discord.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import androidx.annotation.ColorInt
import com.discord.utilities.view.text.SimpleDraweeSpanTextView

/**
 * A name that can be drawn more than once per frame, for the display-name effects that need it.
 *
 * A `TextView` paints through one `Paint`, so `FILL_AND_STROKE` strokes in whatever the fill is
 * using — the gradient, for Toon — and an outline in its own colour is unreachable in a single
 * pass. Two of the effects need exactly that, and Pop needs a whole second copy of the text offset
 * behind the first.
 *
 * Each extra pass draws the [android.text.Layout] directly instead of going through
 * `super.onDraw`, because `TextView` resets the paint's colour from its own text colour on every
 * draw and would undo the stroke set up beforehand.
 */
class DisplayNameTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SimpleDraweeSpanTextView(context, attrs, defStyleAttr) {

    /** An outline drawn under the fill, in its own colour. */
    private var strokeColor: Int? = null
    private var strokeWidth: Float = 0f

    /** A second copy of the text drawn behind and below the fill. */
    private var backLayerColor: Int? = null
    private var backLayerOffset: Float = 0f
    private var backLayerStrokeWidth: Float = 0f

    fun setNameStroke(@ColorInt color: Int?, width: Float) {
        if (strokeColor == color && strokeWidth == width) return
        strokeColor = color
        strokeWidth = width
        invalidate()
    }

    fun setNameBackLayer(@ColorInt color: Int?, offset: Float, strokeWidth: Float) {
        if (backLayerColor == color && backLayerOffset == offset) return
        backLayerColor = color
        backLayerOffset = offset
        backLayerStrokeWidth = strokeWidth
        invalidate()
    }

    fun clearNameLayers() {
        setNameStroke(null, 0f)
        setNameBackLayer(null, 0f, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        backLayerColor?.let { color ->
            drawPass(canvas, color, backLayerStrokeWidth, backLayerOffset, fill = true)
        }
        strokeColor?.let { color -> drawPass(canvas, color, strokeWidth, 0f, fill = false) }
        super.onDraw(canvas)
    }

    /**
     * One extra copy of the text in a flat colour.
     *
     * The paint is borrowed and put back exactly as it was: it is the same instance the fill pass
     * is about to use, and a shader or style left behind would repaint the real text.
     */
    private fun drawPass(
        canvas: Canvas,
        @ColorInt color: Int,
        width: Float,
        offsetY: Float,
        fill: Boolean,
    ) {
        val layout = layout ?: return
        val paint = paint
        val savedColor = paint.color
        val savedShader: Shader? = paint.shader
        val savedStyle = paint.style
        val savedStrokeWidth = paint.strokeWidth

        paint.shader = null
        paint.color = color
        paint.style = if (fill) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        paint.strokeWidth = width
        // The glow belongs to the fill; repeating it under every pass would stack it.
        paint.clearShadowLayer()

        canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat() + offsetY)
        layout.draw(canvas)
        canvas.restore()

        paint.color = savedColor
        paint.shader = savedShader
        paint.style = savedStyle
        paint.strokeWidth = savedStrokeWidth
        // `Paint` has no shadow getters, so the view's own copy of the values is the record.
        if (shadowColor != Color.TRANSPARENT) {
            paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        }
    }
}
