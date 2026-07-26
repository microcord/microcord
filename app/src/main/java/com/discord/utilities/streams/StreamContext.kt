package com.discord.utilities.streams

import com.discord.models.guild.Guild
import com.discord.models.user.User
import com.discord.stores.StoreApplicationStreamPreviews

/**
 * Everything a surface needs to render somebody's Go Live stream: who is streaming, where, whether
 * the viewer may join, and the last thumbnail we fetched.
 *
 * The reference is a `data class` assembled by `StreamContextService`. That service has not been
 * reconstructed yet, so this stays an interface — nothing in the project constructs one, and the
 * members below are the reference's fields verbatim so the eventual data class is a drop-in
 * replacement. Until it lands, every stream-driven surface reads `null` and hides itself.
 */
interface StreamContext {
    val stream: ApplicationStream

    /** The guild the stream is being broadcast in; `null` for a private-call stream. */
    val guild: Guild?

    val preview: StoreApplicationStreamPreviews.StreamPreview?

    /** Whether the viewer may join the stream's voice channel, and if not, why. */
    val joinability: Joinability

    val user: User

    /** The streamer's per-guild nickname, when they have one. */
    val userNickname: String?

    /** The viewer is already in the stream's voice channel. */
    val isCurrentUserParticipating: Boolean

    /** The stream belongs to the viewer. */
    val isSelfStream: Boolean

    interface ApplicationStream {
        val encodedStreamKey: String
    }

    enum class Joinability {
        CAN_CONNECT,
        VOICE_CHANNEL_FULL,
        MISSING_PERMISSIONS,
    }
}
