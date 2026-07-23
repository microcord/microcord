package com.discord.views.stages

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

class SpeakersRecyclerView(context: Context, attrs: AttributeSet?) : RecyclerView(context, attrs, 0) {
    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}
