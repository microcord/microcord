package com.discord.widgets.voice.fullscreen.stage

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.locale.LocaleManager
import java.text.NumberFormat

/**
 * The live stage's title block: topic, channel name, the public badge, and the speaker / listener /
 * blocked counts. Each count and its icon hide together when that count is zero.
 */
class DetailsViewHolder(adapter: StageCallAdapter) :
    StageCallViewHolder(R.layout.widget_stage_channel_details, adapter) {

    private val topic: TextView =
        requireNotNull(itemView.findViewById(R.id.widget_stage_channel_details_topic))
    private val name: TextView =
        requireNotNull(itemView.findViewById(R.id.widget_stage_channel_details_name))
    private val speakerIcon: ImageView =
        requireNotNull(itemView.findViewById(R.id.widget_stage_channel_details_speaker_icon))
    private val speakerCount: TextView =
        requireNotNull(itemView.findViewById(R.id.widget_stage_channel_details_speaker_count))
    private val listenerIcon: ImageView =
        requireNotNull(itemView.findViewById(R.id.widget_stage_channel_details_listener_icon))
    private val listenerCount: TextView =
        requireNotNull(itemView.findViewById(R.id.widget_stage_channel_details_listener_count))
    private val blockedIcon: ImageView =
        requireNotNull(itemView.findViewById(R.id.widget_stage_channel_details_blocked_icon))
    private val blockedCount: TextView =
        requireNotNull(itemView.findViewById(R.id.widget_stage_channel_details_blocked_count))
    private val publicDot: ImageView = requireNotNull(itemView.findViewById(R.id.public_dot))
    private val publicIcon: ImageView = requireNotNull(itemView.findViewById(R.id.public_icon))
    private val publicText: TextView = requireNotNull(itemView.findViewById(R.id.public_text))

    private val numberFormatter: NumberFormat =
        NumberFormat.getNumberInstance(LocaleManager().getPrimaryLocale(itemView.context))

    override fun onConfigure(position: Int, data: StageCallItem) {
        super.onConfigure(position, data)
        val item = data as StageCallItem.DetailsItem

        val hasSpeakers = item.numSpeakers > 0
        val hasAudience = item.numAudience > 0
        val hasBlocked = item.numBlocked > 0

        speakerIcon.visibleIf(hasSpeakers)
        speakerCount.visibleIf(hasSpeakers)
        speakerCount.text = numberFormatter.format(item.numSpeakers)

        listenerIcon.visibleIf(hasAudience)
        listenerCount.visibleIf(hasAudience)
        listenerCount.text = numberFormatter.format(item.numAudience)

        blockedIcon.visibleIf(hasBlocked)
        blockedCount.visibleIf(hasBlocked)
        blockedCount.text = numberFormatter.format(item.numBlocked)

        name.text = item.displayName
        topic.text = item.topic

        publicDot.visibleIf(item.isPublic)
        publicIcon.visibleIf(item.isPublic)
        publicText.visibleIf(item.isPublic)

        // With no speakers the audience roll follows immediately, so the block adds its own gap.
        itemView.updateBottomMargin(
            if (!hasSpeakers && hasAudience) dpToPixels(NO_SPEAKERS_BOTTOM_MARGIN_DP) else 0,
        )
    }

    private fun View.visibleIf(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private companion object {
        const val NO_SPEAKERS_BOTTOM_MARGIN_DP = 24
    }
}
