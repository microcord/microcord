package com.discord.api.guild

import com.discord.api.guildmember.GuildMember
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelReadState

/**
 * Periodic unread and voice state for a guild the client is not subscribed to.
 *
 * Strictly a delta: anything absent is unchanged, and the only removals are the ones named in
 * [removedVoiceStates]. Treating it as a full picture would wipe every voice state the event did
 * not happen to mention.
 */
data class PassiveUpdateV2(
    val guildId: Long,
    val updatedChannels: List<ModelReadState> = emptyList(),
    val updatedVoiceStates: List<VoiceState> = emptyList(),
    /** User ids that left voice; the states themselves are not repeated. */
    val removedVoiceStates: List<Long> = emptyList(),
    /** The members behind [updatedVoiceStates], so the voice list can render them. */
    val updatedMembers: List<GuildMember> = emptyList(),
)
