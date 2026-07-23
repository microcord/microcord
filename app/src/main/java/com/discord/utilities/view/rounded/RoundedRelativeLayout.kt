package com.discord.utilities.view.rounded

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.RelativeLayout
import com.discord.R

class RoundedRelativeLayout : RelativeLayout {
    private val roundedViewCoordinator = RoundedCornerViewCoordinator()

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initialize(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        initialize(attrs)
    }

    private fun initialize(attrs: AttributeSet?) {
        setWillNotDraw(false)
        context.obtainStyledAttributes(attrs, R.styleable.RoundedRelativeLayout).run {
            try {
                roundedViewCoordinator.setRadii(
                    getDimension(R.styleable.RoundedRelativeLayout_topLeftRadius, 0f),
                    getDimension(R.styleable.RoundedRelativeLayout_topRightRadius, 0f),
                    getDimension(R.styleable.RoundedRelativeLayout_bottomLeftRadius, 0f),
                    getDimension(R.styleable.RoundedRelativeLayout_bottomRightRadius, 0f),
                )
            } finally {
                recycle()
            }
        }
    }

    fun updateTopLeftRadius(radius: Float) = roundedViewCoordinator.updateTopLeft(this, radius)
    fun updateTopRightRadius(radius: Float) = roundedViewCoordinator.updateTopRight(this, radius)
    fun updateBottomLeftRadius(radius: Float) = roundedViewCoordinator.updateBottomLeft(this, radius)
    fun updateBottomRightRadius(radius: Float) = roundedViewCoordinator.updateBottomRight(this, radius)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        roundedViewCoordinator.onSizeChanged(w, h)
    }

    override fun draw(canvas: Canvas) = roundedViewCoordinator.draw(canvas) { super.draw(canvas) }
}
