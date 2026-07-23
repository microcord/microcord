package com.discord.views.calls

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R

class StageCallVisitCommunityView(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs, 0) {
    init {
        val content = LayoutInflater.from(context)
            .inflate(R.layout.view_stage_channel_visit_community, this, false)
        addView(content)
        requireNotNull<android.view.View>(content.findViewById(R.id.visit_community_icon))
        requireNotNull<android.view.View>(content.findViewById(R.id.visit_community_icon_text))
        requireNotNull<android.view.View>(content.findViewById(R.id.visit_community_text))
    }
}
