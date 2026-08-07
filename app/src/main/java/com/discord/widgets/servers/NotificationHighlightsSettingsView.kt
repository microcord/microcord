package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.views.CheckedSetting
import com.google.android.material.card.MaterialCardView

class NotificationHighlightsSettingsView(context: Context, attrs: AttributeSet) :
    LinearLayout(context, attrs) {
    data class ViewState(
        val isMuted: Boolean,
        val notifyHighlights: Int,
        val serverTooLarge: Boolean,
    )

    private val learnMore: TextView
    private val highlightsSwitch: CheckedSetting
    private val warning: MaterialCardView

    init {
        val content = LayoutInflater.from(context)
            .inflate(R.layout.notification_highlights_settings_view, this, false)
        addView(content)
        learnMore = requireNotNull(content.findViewById(R.id.highlights_learn_more))
        highlightsSwitch = requireNotNull(content.findViewById(R.id.highlights_switch))
        warning = requireNotNull(content.findViewById(R.id.highlights_warning))
    }

    fun updateView(viewState: ViewState, onToggled: (Int) -> Unit) {
        highlightsSwitch.isChecked =
            viewState.notifyHighlights != HIGHLIGHTS_DISABLED && !viewState.isMuted
        if (viewState.isMuted) {
            highlightsSwitch.setDisabledTooltip(null)
        } else {
            highlightsSwitch.setContainerClickListener {
                onToggled(
                    if (viewState.notifyHighlights == HIGHLIGHTS_DISABLED) HIGHLIGHTS_ENABLED
                    else HIGHLIGHTS_DISABLED,
                )
            }
        }
        learnMore.setOnClickListener {
            val uri = Uri.parse(SUPPORT_URL)
            if (uri.scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
        warning.visibility = if (
            viewState.serverTooLarge && !viewState.isMuted &&
            viewState.notifyHighlights != HIGHLIGHTS_DISABLED
        ) View.VISIBLE else View.GONE
    }

    private companion object {
        const val HIGHLIGHTS_DISABLED = 1
        const val HIGHLIGHTS_ENABLED = 2
        const val SUPPORT_URL = "https://support.discord.com/hc/en-us/articles/5304469213079"
    }
}
