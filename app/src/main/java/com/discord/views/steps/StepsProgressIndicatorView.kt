package com.discord.views.steps

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R

class StepsProgressIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private var totalStepCount = 0

    init {
        orientation = HORIZONTAL
    }

    fun setTotalStepCount(totalStepCount: Int) {
        this.totalStepCount = totalStepCount
        rebuild()
        setCurrentStepIndex(0)
    }

    fun setCurrentStepIndex(currentStepIndex: Int) {
        if (currentStepIndex >= totalStepCount) {
            Log.e(javaClass.simpleName, "Current step cannot be greater than the total step count")
            return
        }
        if (childCount != totalStepCount) rebuild()
        for (index in 0 until childCount) {
            (getChildAt(index) as TextView).apply {
                isEnabled = index <= currentStepIndex
                isSelected = index == currentStepIndex
                if (isSelected) {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    text = (index + 1).toString()
                } else {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 0f)
                    text = null
                }
            }
        }
    }

    private fun rebuild() {
        removeAllViews()
        repeat(totalStepCount) {
            addView(
                LayoutInflater.from(context).inflate(
                    R.layout.steps_progress_indicator_dot_view,
                    this,
                    false,
                ) as TextView,
            )
        }
    }
}
