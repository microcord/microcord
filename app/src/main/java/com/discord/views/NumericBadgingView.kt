package com.discord.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.discord.R

class NumericBadgingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CutoutView(context, attrs) {
    private val textSize = (12 * resources.displayMetrics.density).toInt().toFloat()
    private val verticalPadding = (8 * resources.displayMetrics.density).toInt()
    private val cutoutInset = (4 * resources.displayMetrics.density).toInt().toFloat()
    private val badgeRect = RectF()
    private var badgeText = ""
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = this@NumericBadgingView.textSize
        typeface = resolveTypeface()
    }

    init {
        setWillNotDraw(false)
        var badgeColor = 0
        var textColor = 0
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.NumericBadgingView).run {
                try {
                    badgeColor = getColor(R.styleable.NumericBadgingView_badge_backgroundColor, 0)
                    textColor = getColor(R.styleable.NumericBadgingView_badge_textColor, 0)
                } finally {
                    recycle()
                }
            }
        }
        badgePaint.color = if (badgeColor == 0) ContextCompat.getColor(context, R.color.status_red_500) else badgeColor
        textPaint.color = if (textColor == 0) ContextCompat.getColor(context, R.color.white) else textColor
    }

    fun setBadgeNumber(number: Int) {
        if (number <= 0) {
            badgeText = ""
            style = None
            return
        }
        badgeText = if (number >= 100) "99+" else number.toString()
        val height = textPaint.textSize + verticalPadding
        val width = if (number < 10) height else textPaint.measureText(badgeText) + height
        badgeRect.set(-width / 2f, -height / 2f, width / 2f, height / 2f)
        style = BadgeRectStyle(badgeRect, cutoutInset)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (badgeText.isEmpty()) return
        val width = badgeRect.width()
        val height = badgeRect.height()
        val centerX = if (CutoutView.isLtr()) measuredWidth - width / 2f else width / 2f
        val centerY = height / 2f
        canvas.drawRoundRect(
            centerX - width / 2f,
            0f,
            centerX + width / 2f,
            height,
            height / 2f,
            height / 2f,
            badgePaint,
        )
        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(badgeText, centerX, baseline, textPaint)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (isInEditMode) setBadgeNumber(42)
    }

    private fun resolveTypeface(): android.graphics.Typeface? {
        val value = TypedValue()
        context.theme.resolveAttribute(R.attr.font_primary_semibold, value, true)
        return if (value.resourceId == 0) null else ResourcesCompat.getFont(context, value.resourceId)
    }

    private data class BadgeRectStyle(val rect: RectF, val inset: Float) : Style {
        override fun createPath(context: Context, width: Int, height: Int): Path {
            val badgeWidth = rect.width()
            val badgeHeight = rect.height()
            val positioned = if (CutoutView.isLtr()) {
                RectF(width - badgeWidth, 0f, width.toFloat(), badgeHeight)
            } else {
                RectF(0f, 0f, badgeWidth, badgeHeight)
            }
            positioned.inset(-inset, -inset)
            val full = Path().apply {
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            }
            val badge = Path().apply {
                addRoundRect(positioned, positioned.height() / 2f, positioned.height() / 2f, Path.Direction.CW)
            }
            full.op(badge, Path.Op.DIFFERENCE)
            return full
        }
    }
}
