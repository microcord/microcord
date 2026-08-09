package com.discord.widgets.safetyhub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import com.discord.api.safetyhub.AccountStandingState

/** The five-stop line the reference draws under the standing header, one stop per state. */
class AccountStandingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var standing = AccountStandingState.ALL_GOOD

    @ColorInt
    private var accent = Color.TRANSPARENT

    @ColorInt
    private var track = Color.TRANSPARENT

    fun setStanding(state: AccountStandingState, @ColorInt accentColor: Int, @ColorInt trackColor: Int) {
        standing = state
        accent = accentColor
        track = trackColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            View.resolveSize((SELECTED_RADIUS_DP * 2 * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val selectedRadius = SELECTED_RADIUS_DP * density
        val markerRadius = MARKER_RADIUS_DP * density
        val centerY = height / 2f
        val left = selectedRadius
        val right = width - selectedRadius

        trackPaint.color = track
        trackPaint.strokeWidth = TRACK_HEIGHT_DP * density
        canvas.drawLine(left, centerY, right, centerY, trackPaint)

        val states = AccountStandingState.entries
        val step = (right - left) / (states.size - 1)
        states.forEachIndexed { index, state ->
            val isSelected = state == standing
            markerPaint.color = if (isSelected) accent else track
            canvas.drawCircle(
                left + step * index,
                centerY,
                if (isSelected) selectedRadius else markerRadius,
                markerPaint,
            )
        }
    }

    private val density: Float get() = resources.displayMetrics.density

    private companion object {
        const val TRACK_HEIGHT_DP = 2f
        const val MARKER_RADIUS_DP = 4f
        const val SELECTED_RADIUS_DP = 8f
    }
}
