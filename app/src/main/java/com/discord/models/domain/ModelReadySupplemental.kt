package com.discord.models.domain

import com.discord.api.embeddedactivities.EmbeddedActivityInGuild
import com.discord.api.channel.Channel
import com.discord.api.guildmember.GuildMember
import com.discord.api.presence.Presence
import com.discord.api.voice.state.VoiceState
import com.google.gson.annotations.SerializedName

/**
 * The rest of READY, sent separately under `PRIORITIZED_READY_PAYLOAD` so the client can start on
 * the parts it needs first.
 *
 * Whatever appears here is *absent* from READY, so nothing in it is optional in practice: without
 * it there are no friend presences, no voice states, and the DM list is only whatever the server
 * judged hot. Members and presences are deduplicated against READY's `users`.
 */
data class ModelReadySupplemental(
    val guilds: List<SupplementalGuild>? = null,
    @SerializedName("merged_members") val mergedMembers: List<List<GuildMember>>? = null,
    @SerializedName("merged_presences") val mergedPresences: MergedPresences? = null,
    @SerializedName("lazy_private_channels") val lazyPrivateChannels: List<Channel>? = null,
    val disclose: List<String>? = null,
) {
    /** An unavailable guild arrives as nothing but an [id]. */
    data class SupplementalGuild(
        val id: Long = 0,
        @SerializedName("voice_states") val voiceStates: List<VoiceState>? = null,
        @SerializedName("activity_instances") val activityInstances: List<EmbeddedActivityInGuild>? = null,
    )

    /** [guilds] is positional against [ModelReadySupplemental.guilds], not keyed by guild id. */
    data class MergedPresences(
        val friends: List<Presence>? = null,
        val guilds: List<List<Presence>>? = null,
    )
}
