package com.discord.widgets.servers.community

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R

class CommunitySelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val description: TextView
    private val dropdownIcon: ImageView
    private val subtitle: TextView
    private val title: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_server_settings_community_setting_selector, this)
        description = requireNotNull(findViewById(R.id.server_settings_community_setting_selector_description))
        dropdownIcon = requireNotNull(findViewById(R.id.server_settings_community_setting_selector_dropdown_icon))
        subtitle = requireNotNull(findViewById(R.id.server_settings_community_setting_selector_subtitle))
        title = requireNotNull(findViewById(R.id.server_settings_community_setting_selector_title))
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.CommunitySelectorView, 0, 0).run {
                try {
                    title.text = getText(R.styleable.CommunitySelectorView_csv_title_text)
                    subtitle.text = getText(R.styleable.CommunitySelectorView_csv_subtitle_text)
                    description.text = getText(R.styleable.CommunitySelectorView_csv_description_text)
                    val dropdown = getBoolean(R.styleable.CommunitySelectorView_csv_is_dropdown, true)
                    subtitle.visibility = if (dropdown) View.VISIBLE else View.GONE
                    dropdownIcon.visibility = if (dropdown) View.VISIBLE else View.GONE
                } finally {
                    recycle()
                }
            }
        }
    }

    fun setSubtitle(subtitle: CharSequence) {
        this.subtitle.text = subtitle
    }
}
