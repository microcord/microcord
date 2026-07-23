package com.discord.utilities.view.recycler

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import kotlin.math.roundToInt

class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    var maxHeight: Int = if (attrs == null) {
        0
    } else {
        context.obtainStyledAttributes(attrs, R.styleable.MaxHeightRecyclerView, 0, 0).run {
            try {
                val height = getDimensionPixelSize(R.styleable.MaxHeightRecyclerView_max_height, 0)
                val ratio = getFloat(R.styleable.MaxHeightRecyclerView_max_height_screen_ratio, 0f)
                if (ratio > 0f && ratio < 1f) {
                    (resources.displayMetrics.heightPixels * ratio).roundToInt()
                } else {
                    height
                }
            } finally {
                recycle()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            if (maxHeight > 0) {
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            } else {
                heightMeasureSpec
            },
        )
    }
}
