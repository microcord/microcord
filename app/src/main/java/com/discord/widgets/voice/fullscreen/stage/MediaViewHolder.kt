package com.discord.widgets.voice.fullscreen.stage

import com.discord.R
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.views.calls.VideoCallParticipantView

/**
 * A speaker who is sharing camera or screen, rendered as a live video tile in the stage grid.
 *
 * Unlike the call grid there is no display cutout to avoid and no call chrome to dodge, so the
 * cutout is `null` and the insets are zero; the stream-quality chip is always allowed because the
 * stage's controls do not overlay the tile.
 */
class MediaViewHolder(adapter: StageCallAdapter) :
    StageCallViewHolder(R.layout.widget_stage_channel_speaker_media, adapter) {

    private val participantView: VideoCallParticipantView =
        requireNotNull(itemView.findViewById(R.id.stage_channel_media_speaker_container))

    override fun onConfigure(position: Int, data: StageCallItem) {
        super.onConfigure(position, data)
        val item = data as StageCallItem.MediaItem

        participantView.configure(
            data = item.participantData,
            displayCutout = null,
            isTopRow = false,
            callUiInsets = VideoCallParticipantView.CallUiInsets(),
            showStreamQuality = true,
        )
        participantView.setOnWatchStreamClicked(adapter.onWatchStreamClicked)
        participantView.setOnClickListener {
            adapter.onMediaParticipantTapped(item.userOrStreamParticipant)
        }
        participantView.setOnLongClickListenerConsumeClick {
            adapter.onMediaParticipantLongClicked(item.userOrStreamParticipant)
        }
        participantView.onStreamQualityIndicatorShown = adapter.onStreamQualityIndicatorShown
        participantView.onStreamQualityIndicatorClicked = adapter.onStreamQualityIndicatorClick
    }
}
