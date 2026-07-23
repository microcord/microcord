package com.discord.views

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.text.TextUtilsCompat
import com.discord.R
import java.util.Locale

open class CutoutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    interface Style {
        fun createPath(context: Context, width: Int, height: Int): Path?
    }

    data class CircularBadge(
        val badgeRadius: Int = dpSystem(14),
        val inset: Int = dpSystem(8),
    ) : Style {
        override fun createPath(context: Context, width: Int, height: Int): Path {
            val full = Path().apply {
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            }
            val centerX = if (isLtr()) width - inset.toFloat() else inset.toFloat()
            val badge = Path().apply {
                addCircle(centerX, inset.toFloat(), badgeRadius.toFloat(), Path.Direction.CW)
            }
            full.op(badge, Path.Op.DIFFERENCE)
            return full
        }
    }

    data class End(
        val cutCurveRadiusPx: Int,
        val cutDistanceInwardFromEdgePx: Int,
    ) : Style {
        override fun createPath(context: Context, width: Int, height: Int): Path {
            val radius = cutCurveRadiusPx.toFloat()
            val delta = radius - width / 2f
            val outerX = -delta
            val outerBottom = height + delta
            val diameter = radius * 2f
            return Path().apply {
                if (isLtr()) {
                    moveTo(outerX, outerX)
                    arcTo(
                        RectF(
                            width - cutDistanceInwardFromEdgePx.toFloat(),
                            outerX,
                            width - cutDistanceInwardFromEdgePx + diameter,
                            outerBottom,
                        ),
                        270f,
                        -180f,
                    )
                    lineTo(outerX, outerBottom)
                } else {
                    val right = width + delta
                    moveTo(right, outerX)
                    arcTo(
                        RectF(
                            cutDistanceInwardFromEdgePx - diameter,
                            outerX,
                            cutDistanceInwardFromEdgePx.toFloat(),
                            outerBottom,
                        ),
                        270f,
                        180f,
                    )
                    lineTo(right, outerBottom)
                }
                close()
            }
        }
    }

    data class EndOverlap(val offsetDp: Int) : Style {
        override fun createPath(context: Context, width: Int, height: Int): Path {
            val offset = offsetDp * context.resources.displayMetrics.density
            val radius = height / 2f
            return Path().apply {
                if (isLtr()) {
                    moveTo(0f, 0f)
                    lineTo(width + offset, 0f)
                    arcTo(
                        RectF(width + offset - radius, 0f, width + offset + radius, height.toFloat()),
                        270f,
                        -180f,
                    )
                    lineTo(0f, height.toFloat())
                } else {
                    moveTo(width.toFloat(), 0f)
                    lineTo(-offset, 0f)
                    arcTo(RectF(-offset - radius, 0f, -offset + radius, height.toFloat()), 270f, 180f)
                    lineTo(width.toFloat(), height.toFloat())
                }
                close()
            }
        }
    }

    data object None : Style {
        override fun createPath(context: Context, width: Int, height: Int): Path? = null
    }

    private var clippingPath: Path? = null
    private var measuredCutoutWidth = 0
    private var measuredCutoutHeight = 0

    var style: Style = None
        set(value) {
            field = value
            if (measuredCutoutWidth > 0 && measuredCutoutHeight > 0) {
                clippingPath = value.createPath(context, measuredCutoutWidth, measuredCutoutHeight)
                invalidate()
            }
        }

    var isCutoutEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        setWillNotDraw(false)
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.CutoutView).run {
                try {
                    if (getInt(R.styleable.CutoutView_cutout_style, 0) == 1) {
                        style = CircularBadge(
                            getDimensionPixelSize(R.styleable.CutoutView_cutout_badgeRadius, dpSystem(14)),
                            getDimensionPixelSize(R.styleable.CutoutView_cutout_badgeInset, dpSystem(8)),
                        )
                    }
                } finally {
                    recycle()
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredWidth == measuredCutoutWidth && measuredHeight == measuredCutoutHeight && clippingPath != null) {
            return
        }
        measuredCutoutWidth = measuredWidth
        measuredCutoutHeight = measuredHeight
        clippingPath = style.createPath(context, measuredWidth, measuredHeight)
    }

    override fun draw(canvas: Canvas) {
        val saveCount = canvas.save()
        try {
            if (isCutoutEnabled) clippingPath?.let(canvas::clipPath)
            super.draw(canvas)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (isInEditMode && childCount == 0) {
            addView(android.view.View(context).apply { setBackgroundColor(0xFF00FF00.toInt()) })
            style = CircularBadge()
        }
    }

    companion object {
        private fun dpSystem(value: Int) =
            (value * Resources.getSystem().displayMetrics.density).toInt()

        internal fun isLtr() =
            TextUtilsCompat.getLayoutDirectionFromLocale(Locale.getDefault()) == LAYOUT_DIRECTION_LTR
    }
}
