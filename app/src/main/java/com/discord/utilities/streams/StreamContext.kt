package com.discord.utilities.streams

import com.discord.models.domain.ModelApplicationStream
import com.discord.models.guild.Guild
import com.discord.models.user.User
import com.discord.stores.StoreApplicationStreamPreviews.StreamPreview

/**
 * Everything a surface needs to render somebody's Go Live stream: who is streaming, where, whether
 * the viewer may join, and the last thumbnail we fetched.
 *
 * Produced by [StreamContextService]; there is deliberately no other constructor call site, so
 * every stream surface sees the same joinability decision.
 *
 * Reference: `com.discord.utilities.streams.StreamContext`.
 */
data class StreamContext(
    val stream: ModelApplicationStream,
    /** The guild the stream is being broadcast in; `null` for a private-call stream. */
    val guild: Guild?,
    val preview: StreamPreview?,
    /** Whether the viewer may join the stream's voice channel, and if not, why. */
    val joinability: Joinability,
    val user: User,
    /** The streamer's per-guild nickname, when they have one. */
    val userNickname: String?,
    /** The viewer is already watching this stream. */
    val isCurrentUserParticipating: Boolean,
    /** The stream belongs to the viewer. */
    val isSelfStream: Boolean,
) {
    enum class Joinability {
        CAN_CONNECT,
        VOICE_CHANNEL_FULL,
        MISSING_PERMISSIONS,
    }
}
