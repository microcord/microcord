package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R

class ScreenTitleView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs, 0) {
    private val titleView: TextView
    private val subtitleView: TextView

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.view_screen_title, this, false)
        addView(content)
        titleView = requireNotNull(content.findViewById(R.id.screen_title_title))
        subtitleView = requireNotNull(content.findViewById(R.id.screen_title_subtitle))

        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.ScreenTitleView, 0, 0).run {
                try {
                    getText(R.styleable.ScreenTitleView_stv_title)?.let(::setTitle)
                    setSubtitle(getText(R.styleable.ScreenTitleView_stv_subtitle))
                } finally {
                    recycle()
                }
            }
        }
    }

    fun setTitle(title: CharSequence) {
        titleView.text = title
    }

    fun setSubtitle(subtitle: CharSequence?) {
        subtitleView.text = subtitle
        subtitleView.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}
