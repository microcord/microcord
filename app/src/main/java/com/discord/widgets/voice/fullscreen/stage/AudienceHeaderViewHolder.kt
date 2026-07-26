package com.discord.widgets.voice.fullscreen.stage

import android.widget.TextView
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.utilities.locale.LocaleManager
import java.text.NumberFormat

/** The "Audience — N" heading that opens the listener roll. */
class AudienceHeaderViewHolder(adapter: StageCallAdapter) :
    StageCallViewHolder(R.layout.widget_stage_channel_audience_role, adapter) {

    private val roleText: TextView =
        requireNotNull(itemView.findViewById(R.id.stage_channel_audience_role_text))

    // Group separators are locale-specific, so a 12,000-strong audience reads correctly in every
    // locale rather than as a bare integer.
    private val numberFormatter: NumberFormat =
        NumberFormat.getNumberInstance(LocaleManager().getPrimaryLocale(itemView.context))

    override fun onConfigure(position: Int, data: StageCallItem) {
        super.onConfigure(position, data)
        val item = data as StageCallItem.AudienceHeaderItem
        roleText.text = roleText.i18nFormat(
            R.string.audience_section_header,
            "audienceSize" to numberFormatter.format(item.audienceSize),
        )
    }
}
