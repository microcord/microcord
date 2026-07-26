package com.discord.widgets.voice.fullscreen.stage

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.discord.R
import com.discord.utilities.dimen.dpToPixels

/** The stage's title block before it has gone live: just a title and a subtitle. */
class PrestartDetailsViewHolder(adapter: StageCallAdapter) :
    StageCallViewHolder(R.layout.widget_stage_channel_prestart_details, adapter) {

    private val title: TextView = requireNotNull(itemView.findViewById(R.id.title))
    private val subtitle: TextView = requireNotNull(itemView.findViewById(R.id.subtitle))

    override fun onConfigure(position: Int, data: StageCallItem) {
        super.onConfigure(position, data)
        val item = data as StageCallItem.PreStartDetailsItem
        title.text = item.title
        subtitle.text = item.subtitle

        // With no speakers the audience roll follows immediately, so the block adds its own gap;
        // otherwise the speaker grid provides the spacing.
        val hasSpeakers = item.numSpeakers > 0
        val hasAudience = item.numAudience > 0
        itemView.updateBottomMargin(
            if (!hasSpeakers && hasAudience) dpToPixels(NO_SPEAKERS_BOTTOM_MARGIN_DP) else 0,
        )
    }

    private companion object {
        const val NO_SPEAKERS_BOTTOM_MARGIN_DP = 24
    }
}

internal fun View.updateBottomMargin(bottomMargin: Int) {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    params.bottomMargin = bottomMargin
    layoutParams = params
}
