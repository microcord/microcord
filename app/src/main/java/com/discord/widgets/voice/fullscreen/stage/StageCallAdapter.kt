package com.discord.widgets.voice.fullscreen.stage

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.api.channel.Channel
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.calls.VideoCallParticipantView
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.voice.fullscreen.CallParticipant

/**
 * The fullscreen stage channel's single list: details block, speaker grid, divider, audience roll.
 *
 * Everything is laid out on a [SPAN_COUNT]-column grid so rows can share a line at different
 * widths. A speaker takes the full width alone, half the width in a pair, and a third otherwise;
 * audience rows always sit three-up; every other row spans the full width. [itemDecoration] then
 * adds the gutters between speakers, which cannot come from the span sizes because the grid has no
 * spacing of its own.
 */
class StageCallAdapter(
    recyclerView: RecyclerView,
    private val fragmentManager: FragmentManager,
    val onMediaParticipantTapped: (CallParticipant.UserOrStreamParticipant) -> Unit,
    val onMediaParticipantLongClicked: (CallParticipant.UserOrStreamParticipant) -> Unit,
    val onWatchStreamClicked: (String) -> Unit,
    val onStreamQualityIndicatorShown: (
        VideoCallParticipantView.StreamResolution,
        VideoCallParticipantView.StreamFps?,
    ) -> Unit,
    val onStreamQualityIndicatorClick: (
        VideoCallParticipantView.StreamResolution,
        VideoCallParticipantView.StreamFps?,
    ) -> Unit,
) : MGRecyclerAdapterSimple<StageCallItem>(recyclerView) {

    private var numSpeakers = 0

    val spanSizeLookup: GridLayoutManager.SpanSizeLookup =
        object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                spanSizeFor(getItem(position).type, numSpeakers)
        }

    /**
     * Gutters between speaker tiles. The span index identifies the column: 0 is leading, the
     * highest index is trailing, and the middle column of a three-up row is padded on both sides.
     */
    val itemDecoration: RecyclerView.ItemDecoration = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            if (getItem(position).type != StageCallItem.TYPE_SPEAKER) return

            val spanIndex =
                (view.layoutParams as GridLayoutManager.LayoutParams).spanIndex
            when {
                numSpeakers == 2 && spanIndex == 0 -> outRect.right = dpToPixels(PAIR_GUTTER_DP)
                spanIndex == SPEAKER_HALF_SPAN_COUNT -> outRect.left = dpToPixels(PAIR_GUTTER_DP)
                numSpeakers > 2 && spanIndex == 0 -> outRect.right = dpToPixels(TRIPLE_GUTTER_DP)
                spanIndex == SPEAKER_THIRD_SPAN_COUNT * 2 ->
                    outRect.left = dpToPixels(TRIPLE_GUTTER_DP)
                spanIndex == SPEAKER_THIRD_SPAN_COUNT -> outRect.set(
                    dpToPixels(TRIPLE_MIDDLE_GUTTER_DP),
                    0,
                    dpToPixels(TRIPLE_MIDDLE_GUTTER_DP),
                    0,
                )
            }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, StageCallItem> = when (viewType) {
        StageCallItem.TYPE_DETAILS -> DetailsViewHolder(this)
        StageCallItem.TYPE_SPEAKER -> SpeakerViewHolder(this)
        StageCallItem.TYPE_AUDIENCE_HEADER -> AudienceHeaderViewHolder(this)
        StageCallItem.TYPE_AUDIENCE -> AudienceViewHolder(this)
        StageCallItem.TYPE_DIVIDER -> DividerViewHolder(this)
        StageCallItem.TYPE_PRE_START_DETAILS -> PrestartDetailsViewHolder(this)
        StageCallItem.TYPE_MEDIA -> MediaViewHolder(this)
        else -> error("Invalid view type: $viewType")
    }

    /**
     * @param numSpeakers drives the speaker span size, so it must be applied before the new rows
     *   are laid out rather than derived from them afterwards.
     */
    fun setData(data: List<StageCallItem>, numSpeakers: Int) {
        this.numSpeakers = numSpeakers
        setData(data)
    }

    internal fun onItemClick(item: StageCallItem) {
        if (item is StageCallParticipantItem) {
            openUserSheet(item.voiceUser.user.id, item.channel)
        }
    }

    private fun openUserSheet(userId: Long, channel: Channel) {
        WidgetUserSheet.show(
            fragmentManager = fragmentManager,
            userId = userId,
            channelId = channel.id,
            guildId = channel.guildId,
            isVoiceContext = true,
        )
    }

    companion object {
        const val SPAN_COUNT = 12
        const val AUDIENCE_SPAN_COUNT = 3
        const val SPEAKER_FULL_SPAN_COUNT = 12
        const val SPEAKER_HALF_SPAN_COUNT = 6
        const val SPEAKER_THIRD_SPAN_COUNT = 4

        private const val PAIR_GUTTER_DP = 4
        private const val TRIPLE_GUTTER_DP = 5.33f
        private const val TRIPLE_MIDDLE_GUTTER_DP = 2.67f

        /** Columns a row of the given [type] occupies out of [SPAN_COUNT]. */
        internal fun spanSizeFor(type: Int, numSpeakers: Int): Int = when (type) {
            StageCallItem.TYPE_SPEAKER -> when (numSpeakers) {
                1 -> SPEAKER_FULL_SPAN_COUNT
                2 -> SPEAKER_HALF_SPAN_COUNT
                else -> SPEAKER_THIRD_SPAN_COUNT
            }
            StageCallItem.TYPE_AUDIENCE -> AUDIENCE_SPAN_COUNT
            else -> SPAN_COUNT
        }
    }
}
