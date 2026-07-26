package com.discord.widgets.voice.fullscreen.stage

import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.views.calls.VideoCallParticipantView
import com.discord.widgets.voice.fullscreen.CallParticipant

/**
 * One row of the fullscreen stage-channel list.
 *
 * The list is a single recycler mixing the stage's header block, its speaker grid, a divider and
 * the audience roll, so every row shares one [type] space. [key] is stable per row — per user for
 * the participant rows — which is what lets a speaker keep their tile (and its video renderer)
 * while the rest of the stage churns.
 */
sealed class StageCallItem(
    override val key: String,
    override val type: Int,
) : MGRecyclerDataPayload {

    /** Which of a speaker's two possible media tiles a [MediaItem] represents. */
    enum class MediaType { USER, STREAM }

    /** The stage's title block: topic, name and the connected/speaker/audience counts. */
    data class DetailsItem(
        val topic: String?,
        val displayName: String,
        val numUsersConnected: Int,
        val numSpeakers: Int,
        val numAudience: Int,
        val numBlocked: Int,
        val isPublic: Boolean,
    ) : StageCallItem("details", TYPE_DETAILS)

    /** The title block shown before the stage has started. */
    data class PreStartDetailsItem(
        val title: String,
        val subtitle: String,
        val numSpeakers: Int,
        val numAudience: Int,
    ) : StageCallItem("pre-start-details", TYPE_PRE_START_DETAILS)

    /** A speaker's avatar tile in the top grid. */
    data class SpeakerItem(
        override val voiceUser: StoreVoiceParticipants.VoiceUser,
        override val channel: Channel,
        val isModerator: Boolean,
        /** Distinguishes otherwise identical rows so the grid can re-flow without re-binding. */
        val positionKey: String,
        val speakersPerRow: Int,
        val isBlocked: Boolean,
    ) : StageCallItem("speaker-${voiceUser.user.id}", TYPE_SPEAKER), StageCallParticipantItem

    /** A speaker who is sharing camera or screen, rendered as a video tile instead of an avatar. */
    data class MediaItem(
        override val voiceUser: StoreVoiceParticipants.VoiceUser,
        override val channel: Channel,
        val isModerator: Boolean,
        val isBlocked: Boolean,
        val mediaType: MediaType,
        val userOrStreamParticipant: CallParticipant.UserOrStreamParticipant,
    ) : StageCallItem("media-${voiceUser.user.id}-$mediaType", TYPE_MEDIA), StageCallParticipantItem {

        val participantData: VideoCallParticipantView.ParticipantData =
            userOrStreamParticipant.participantData
    }

    /** The "Audience — N" heading between the speaker grid and the audience roll. */
    data class AudienceHeaderItem(val audienceSize: Int) :
        StageCallItem("audience-header", TYPE_AUDIENCE_HEADER)

    /**
     * One listener. Ordering is by hoisted role rather than by name, so role groups stay together
     * in the roll.
     */
    data class AudienceItem(
        override val voiceUser: StoreVoiceParticipants.VoiceUser,
        override val channel: Channel,
        val hoistedGuildRole: GuildRole?,
        val isBlocked: Boolean,
    ) : StageCallItem("audience-${voiceUser.user.id}", TYPE_AUDIENCE),
        StageCallParticipantItem,
        Comparable<AudienceItem> {

        override fun compareTo(other: AudienceItem): Int =
            RoleUtils.ROLE_COMPARATOR.compare(hoistedGuildRole, other.hoistedGuildRole)
    }

    object DividerItem : StageCallItem("divider", TYPE_DIVIDER)

    companion object {
        const val TYPE_DETAILS = 0
        const val TYPE_SPEAKER = 1
        const val TYPE_AUDIENCE_HEADER = 2
        const val TYPE_AUDIENCE = 3
        const val TYPE_DIVIDER = 4
        const val TYPE_PRE_START_DETAILS = 5
        const val TYPE_MEDIA = 6
    }
}
