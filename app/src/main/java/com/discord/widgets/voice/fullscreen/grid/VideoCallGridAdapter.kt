package com.discord.widgets.voice.fullscreen.grid

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.DisplayCutoutCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.views.calls.VideoCallParticipantView
import com.discord.widgets.voice.fullscreen.CallParticipant

/**
 * The fullscreen call's video grid.
 *
 * Diffs on [CallParticipant.id] so a tile keeps its view — and therefore its attached video
 * renderer — while the participant list churns around it. [spanSizeLookup] handles the two cases
 * where a tile is wider than one cell: a two-person call gives each tile the full width, and an odd
 * participant count stretches the last tile so the grid has no gap.
 *
 * @param spanCount columns in portrait / rows in landscape; the host recomputes it on rotation and
 *   rebuilds the adapter, so it is fixed for the adapter's lifetime.
 */
class VideoCallGridAdapter(
    private val onParticipantTapped: (CallParticipant) -> Unit,
    private val onParticipantLongClicked: (CallParticipant) -> Unit,
    private val onWatchStreamClicked: (String) -> Unit,
    private val onStreamQualityIndicatorShown: (
        VideoCallParticipantView.StreamResolution,
        VideoCallParticipantView.StreamFps?,
    ) -> Unit,
    private val onStreamQualityIndicatorClick: (
        VideoCallParticipantView.StreamResolution,
        VideoCallParticipantView.StreamFps?,
    ) -> Unit,
    private val spanCount: Int,
) : RecyclerView.Adapter<VideoCallGridViewHolder>() {

    /** Space the call chrome occupies, so a tile can keep its own overlays clear of it. */
    data class CallUiInsets(val top: Int, val bottom: Int, val left: Int, val right: Int)

    private var data: List<CallParticipant> = emptyList()
    private var displayCutout: DisplayCutoutCompat? = null
    private var callUiInsets = CallUiInsets(0, 0, 0, 0)
    private var controlsVisible = false

    /** Count of activity tiles in the current dataset; the host uses it for analytics. */
    var embeddedActivityParticipantsCount = 0
        private set

    val spanSizeLookup: GridLayoutManager.SpanSizeLookup =
        object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = when {
                itemCount == 2 -> FULL_SPAN
                itemCount % 2 == 1 && position == data.lastIndex -> FULL_SPAN
                else -> 1
            }
        }

    override fun getItemCount(): Int = data.size

    /**
     * A user contributes two possible tiles — camera and screen share — so the screen-share tile
     * offsets its id by one to stay distinct under stable ids.
     */
    override fun getItemId(position: Int): Long = when (val participant = data[position]) {
        is CallParticipant.UserOrStreamParticipant -> {
            val participantData = participant.participantData
            val userId = participantData.voiceParticipant.user.id
            when (participantData.type) {
                VideoCallParticipantView.ParticipantData.Type.DEFAULT -> userId
                VideoCallParticipantView.ParticipantData.Type.APPLICATION_STREAMING -> userId + 1L
            }
        }
        is CallParticipant.EmbeddedActivityParticipant -> participant.application.id
    }

    override fun getItemViewType(position: Int): Int = when (data[position]) {
        is CallParticipant.UserOrStreamParticipant -> USER_OR_STREAM_VIEW_TYPE
        is CallParticipant.EmbeddedActivityParticipant -> EMBEDDED_ACTIVITY_VIEW_TYPE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoCallGridViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            USER_OR_STREAM_VIEW_TYPE -> VideoCallGridViewHolder.UserOrStream(
                inflater.inflate(R.layout.video_call_grid_item, parent, false)
                    as VideoCallParticipantView,
            )
            EMBEDDED_ACTIVITY_VIEW_TYPE -> VideoCallGridViewHolder.EmbeddedActivity(
                inflater.inflate(R.layout.video_call_grid_item_embedded_activity, parent, false),
            )
            else -> error("invalid view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: VideoCallGridViewHolder, position: Int) {
        when (holder) {
            is VideoCallGridViewHolder.UserOrStream -> holder.configure(
                callParticipant = data[position] as CallParticipant.UserOrStreamParticipant,
                onTapped = onParticipantTapped,
                onLongClicked = onParticipantLongClicked,
                onWatchStreamClicked = onWatchStreamClicked,
                displayCutout = displayCutout,
                spanCount = spanCount,
                onStreamQualityIndicatorShown = onStreamQualityIndicatorShown,
                onStreamQualityIndicatorClicked = onStreamQualityIndicatorClick,
                callUiInsets = callUiInsets,
                controlsVisible = controlsVisible,
            )
            is VideoCallGridViewHolder.EmbeddedActivity -> holder.configure(
                callParticipant = data[position] as CallParticipant.EmbeddedActivityParticipant,
                onTapped = onParticipantTapped,
            )
        }
    }

    fun setData(data: List<CallParticipant>) {
        val diff = DiffUtil.calculateDiff(diffCallback(this.data, data), true)
        this.data = data
        diff.dispatchUpdatesTo(this)
        embeddedActivityParticipantsCount =
            data.count { it is CallParticipant.EmbeddedActivityParticipant }
    }

    fun notifyCallControlsVisibilityChanged(controlsVisible: Boolean) {
        this.controlsVisible = controlsVisible
        notifyDataSetChanged()
    }

    /**
     * In landscape the cutout can fall next to any tile in the leading column, so every tile is
     * rebound; in portrait only the first row can touch it.
     */
    fun setDisplayCutout(displayCutout: DisplayCutoutCompat?, isLandscape: Boolean) {
        this.displayCutout = displayCutout
        if (isLandscape) notifyDataSetChanged() else notifyItemRangeChanged(0, spanCount)
    }

    fun setInsetsForAvoidingCallUiOverlap(top: Int, bottom: Int, left: Int, right: Int) {
        callUiInsets = CallUiInsets(top, bottom, left, right)
        notifyDataSetChanged()
    }

    private fun diffCallback(oldData: List<CallParticipant>, newData: List<CallParticipant>) =
        object : DiffUtil.Callback() {
            override fun getOldListSize() = oldData.size
            override fun getNewListSize() = newData.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldData[oldItemPosition].id == newData[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldData[oldItemPosition] == newData[newItemPosition]
        }

    private companion object {
        const val USER_OR_STREAM_VIEW_TYPE = 0
        const val EMBEDDED_ACTIVITY_VIEW_TYPE = 1
        const val FULL_SPAN = 2
    }
}
