package com.discord.widgets.servers.community

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.cardview.widget.CardView
import com.discord.R

class CommunityGetStartedHeaderIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CardView(context, attrs, defStyleAttr) {
    init {
        LayoutInflater.from(context).inflate(R.layout.view_community_get_started_header, this)
        val icon = requireNotNull<ImageView>(findViewById(R.id.community_get_started_header_icon))
        val title = requireNotNull<TextView>(findViewById(R.id.community_get_started_header_icon_text))
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.CommunityGetStartedHeaderIconView).run {
                try {
                    icon.setImageDrawable(
                        AppCompatResources.getDrawable(
                            context,
                            getResourceId(R.styleable.CommunityGetStartedHeaderIconView_chi_icon, 0),
                        ),
                    )
                    title.text = getText(R.styleable.CommunityGetStartedHeaderIconView_chi_title_text)
                } finally {
                    recycle()
                }
            }
        }
    }
}
