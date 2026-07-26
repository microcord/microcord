package com.discord.api.stageinstance

import com.discord.api.guild.GuildFeature
import com.discord.api.guildmember.GuildMember

/** The guild a recommended stage belongs to, trimmed to what the discovery card needs. */
data class RecommendedStageInstanceGuild(
    val id: Long,
    val name: String,
    val icon: String?,
    val features: Set<GuildFeature>,
)

/** One entry of the stage-discovery feed, with the ranking inputs the server used. */
data class RecommendedStageInstance(
    val guild: RecommendedStageInstanceGuild,
    val instance: StageInstance,
    val speakers: List<Long>,
    val sampleSpeakerMembers: List<GuildMember>,
    val participantCount: Int,
    val source: Int,
    val score: Double,
)
