package com.discord.widgets.guildscheduledevent

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.discord.R

class GuildEventPromptView : ConstraintLayout {
    private val icon: ImageView
    private val iconBackground: ImageView
    private val title: TextView
    private val subtitle: TextView

    constructor(context: Context) : super(context) {
        val views = inflateContent()
        icon = views[0] as ImageView
        iconBackground = views[1] as ImageView
        title = views[2] as TextView
        subtitle = views[3] as TextView
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        val views = inflateContent()
        icon = views[0] as ImageView
        iconBackground = views[1] as ImageView
        title = views[2] as TextView
        subtitle = views[3] as TextView
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        val views = inflateContent()
        icon = views[0] as ImageView
        iconBackground = views[1] as ImageView
        title = views[2] as TextView
        subtitle = views[3] as TextView
    }

    fun configure(iconRes: Int, iconBackgroundColorRes: Int, title: CharSequence, subtitle: CharSequence) {
        icon.setImageResource(iconRes)
        ImageViewCompat.setImageTintList(
            iconBackground,
            ColorStateList.valueOf(ContextCompat.getColor(context, iconBackgroundColorRes)),
        )
        this.title.text = title
        this.subtitle.text = subtitle
    }

    private fun inflateContent(): Array<android.view.View> {
        val content = LayoutInflater.from(context).inflate(R.layout.guild_event_prompt_view, this, false)
        addView(content)
        return arrayOf(
            requireNotNull(content.findViewById(R.id.icon)),
            requireNotNull(content.findViewById(R.id.icon_bg)),
            requireNotNull(content.findViewById(R.id.title)),
            requireNotNull(content.findViewById(R.id.subtitle)),
        )
    }
}
