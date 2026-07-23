package com.discord.views.calls

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R

class VoiceCallActiveEventView(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs) {
    init {
        val content = LayoutInflater.from(context).inflate(R.layout.view_voice_call_active_event, this, false)
        addView(content)
        requireNotNull<android.view.View>(content.findViewById(R.id.channel_name))
        requireNotNull<android.view.View>(content.findViewById(R.id.event_icon))
        requireNotNull<android.view.View>(content.findViewById(R.id.event_name))
    }
}
