package com.discord.widgets.voice.fullscreen.grid

import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import androidx.core.view.DisplayCutoutCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.views.PileView
import com.discord.views.calls.VideoCallParticipantView
import com.discord.widgets.voice.fullscreen.CallParticipant
import com.facebook.drawee.view.SimpleDraweeView

/** The two tile kinds the fullscreen call grid can show. */
sealed class VideoCallGridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    /** A person's camera tile or screen-share tile. */
    class UserOrStream(
        private val videoCallParticipantView: VideoCallParticipantView,
    ) : VideoCallGridViewHolder(videoCallParticipantView) {

        @Suppress("LongParameterList")
        fun configure(
            callParticipant: CallParticipant.UserOrStreamParticipant,
            onTapped: (CallParticipant) -> Unit,
            onLongClicked: (CallParticipant) -> Unit,
            onWatchStreamClicked: (String) -> Unit,
            displayCutout: DisplayCutoutCompat?,
            spanCount: Int,
            onStreamQualityIndicatorShown: (
                VideoCallParticipantView.StreamResolution,
                VideoCallParticipantView.StreamFps?,
            ) -> Unit,
            onStreamQualityIndicatorClicked: (
                VideoCallParticipantView.StreamResolution,
                VideoCallParticipantView.StreamFps?,
            ) -> Unit,
            callUiInsets: VideoCallGridAdapter.CallUiInsets,
            controlsVisible: Boolean,
        ) {
            videoCallParticipantView.configure(
                data = callParticipant.participantData,
                displayCutout = displayCutout,
                isTopRow = isAdjacentToCutout(spanCount),
                callUiInsets = callUiInsets.toParticipantInsets(),
                showStreamQuality = controlsVisible,
            )
            videoCallParticipantView.setOnWatchStreamClicked(onWatchStreamClicked)
            videoCallParticipantView.setOnClickListener { onTapped(callParticipant) }
            videoCallParticipantView.setOnLongClickListenerConsumeClick {
                onLongClicked(callParticipant)
            }
            videoCallParticipantView.onStreamQualityIndicatorShown = onStreamQualityIndicatorShown
            videoCallParticipantView.onStreamQualityIndicatorClicked = onStreamQualityIndicatorClicked
        }

        /**
         * Whether this tile borders the edge the display cutout sits on: the first row when the
         * grid scrolls vertically, the first column when it scrolls horizontally in landscape.
         * Only those tiles move their LIVE pill out of the notch's way.
         */
        private fun isAdjacentToCutout(spanCount: Int): Boolean {
            val landscape =
                itemView.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            return if (landscape) {
                bindingAdapterPosition % spanCount == 0
            } else {
                bindingAdapterPosition < spanCount
            }
        }
    }

    /** An embedded activity running in the channel, shown as a tappable preview card. */
    class EmbeddedActivity(itemView: View) : VideoCallGridViewHolder(itemView) {

        private val backgroundImage: SimpleDraweeView =
            requireNotNull(itemView.findViewById(R.id.app_background_image))
        private val backgroundOverlay: View =
            requireNotNull(itemView.findViewById(R.id.app_background_overlay))
        private val participantAvatars: PileView =
            requireNotNull(itemView.findViewById(R.id.activity_participant_avatars))
        private val title: TextView =
            requireNotNull(itemView.findViewById(R.id.activity_preview_title))

        // Fresco re-fetches on every bind unless told the URL is unchanged, and this tile rebinds
        // on every participant update.
        private val backgroundChangeDetector = MGImages.DistinctChangeDetector()

        fun configure(
            callParticipant: CallParticipant.EmbeddedActivityParticipant,
            onTapped: (CallParticipant) -> Unit,
        ) {
            val backgroundAssetUrl = callParticipant.backgroundAsset?.let { asset ->
                IconUtils.getAssetImage(
                    callParticipant.application.id,
                    asset.id.toString(),
                    BACKGROUND_ASSET_SIZE_PX,
                )
            }
            backgroundImage.visibility = if (backgroundAssetUrl != null) View.VISIBLE else View.GONE
            backgroundOverlay.visibility = if (backgroundAssetUrl != null) View.VISIBLE else View.GONE
            if (backgroundAssetUrl != null) {
                MGImages.setImage(
                    backgroundImage,
                    backgroundAssetUrl,
                    changeDetector = backgroundChangeDetector,
                )
            }

            itemView.setOnClickListener { onTapped(callParticipant) }
            title.text = callParticipant.embeddedActivity.name
                ?: itemView.context.getString(R.string.embedded_activities_unknown_activity_name)

            // The reference resolves each avatar at a fixed 16 dp up front rather than letting the
            // pile ask for its own size, so the pile's requested size is deliberately ignored.
            participantAvatars.setItems(
                callParticipant.participantsInActivity.map { member ->
                    val avatarUrl = IconUtils.getForGuildMemberOrUser(
                        member.user,
                        member.guildMember,
                        dpToPixels(AVATAR_SIZE_DP),
                    )
                    PileView.Item(getImageUri = { avatarUrl }, getInitials = null)
                },
            )
        }

        private companion object {
            const val BACKGROUND_ASSET_SIZE_PX = 1024
            const val AVATAR_SIZE_DP = 16
        }
    }
}

/**
 * The grid and the participant view each declare their own inset type; the grid's is the one the
 * reference passes around, so it is converted at this boundary.
 */
private fun VideoCallGridAdapter.CallUiInsets.toParticipantInsets() =
    VideoCallParticipantView.CallUiInsets(left = left, top = top, right = right, bottom = bottom)
