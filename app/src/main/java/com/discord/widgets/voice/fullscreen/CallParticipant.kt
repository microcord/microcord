package com.discord.widgets.voice.fullscreen

import com.discord.api.application.ApplicationAsset
import com.discord.models.commands.Application
import com.discord.models.embeddedactivities.EmbeddedActivity
import com.discord.models.guild.UserGuildMember
import com.discord.views.calls.VideoCallParticipantView

/**
 * One tile in the fullscreen call grid.
 *
 * A tile is either a person — whose camera or screen share may or may not be live — or an embedded
 * activity running in the channel. [id] is the grid's diffing identity: for a person it is the
 * participant data's own composite id (user id plus tile type, so a user's camera tile and their
 * screen-share tile are distinct rows), and for an activity it is the application id.
 */
sealed class CallParticipant {

    abstract val id: String

    /** Whether this tile is currently rendering video, which decides the grid's span layout. */
    abstract fun hasActiveVideo(): Boolean

    data class UserOrStreamParticipant(
        val participantData: VideoCallParticipantView.ParticipantData,
    ) : CallParticipant() {

        override val id: String = participantData.id

        override fun hasActiveVideo(): Boolean =
            participantData.voiceParticipant.voiceState?.selfVideo == true ||
                participantData.applicationStreamState != null
    }

    data class EmbeddedActivityParticipant(
        val embeddedActivity: EmbeddedActivity,
        val participantsInActivity: List<UserGuildMember>,
        val application: Application,
        val backgroundAsset: ApplicationAsset?,
    ) : CallParticipant() {

        override val id: String = application.id.toString()

        /** An activity tile renders a preview image, never a live stream. */
        override fun hasActiveVideo(): Boolean = false
    }
}
