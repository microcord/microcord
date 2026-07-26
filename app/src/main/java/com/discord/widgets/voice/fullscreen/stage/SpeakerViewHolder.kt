package com.discord.widgets.voice.fullscreen.stage

import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.views.calls.StageCallSpeakerView

/**
 * A speaker's avatar tile in the stage grid.
 *
 * The tile's height comes from how many speakers share the row: a lone speaker gets a 16:9 tile
 * sized by its own width, a pair and a trio each get a fixed height. Everything inside the tile —
 * avatar, speaking pulse, mute/deafen/moderator indicators and the representative-colour
 * background — is [StageCallSpeakerView]'s own business.
 */
class SpeakerViewHolder(adapter: StageCallAdapter) :
    StageCallViewHolder(R.layout.widget_stage_channel_speaker, adapter) {

    private val speakerView: StageCallSpeakerView =
        requireNotNull(itemView.findViewById(R.id.stage_channel_speaker_container))

    override fun onConfigure(position: Int, data: StageCallItem) {
        super.onConfigure(position, data)
        val item = data as StageCallItem.SpeakerItem

        val params = speakerView.layoutParams as ConstraintLayout.LayoutParams
        params.height = when (item.speakersPerRow) {
            1 -> 0
            2 -> itemView.resources.getDimensionPixelSize(R.dimen.speaker_height_two_up)
            else -> itemView.resources.getDimensionPixelSize(R.dimen.speaker_height_three_up)
        }
        // Only the full-width tile derives its height from its width; the others are fixed, and a
        // ratio left in place would fight the explicit height.
        params.dimensionRatio = if (item.speakersPerRow == 1) FULL_WIDTH_RATIO else null
        speakerView.layoutParams = params

        speakerView.configure(
            voiceUser = item.voiceUser,
            isModerator = item.isModerator,
            isBlocked = item.isBlocked,
        )
    }

    private companion object {
        const val FULL_WIDTH_RATIO = "H,16:9"
    }
}
