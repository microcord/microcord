package com.discord.api.guild

import com.discord.api.utcdatetime.UtcDateTime

/** Anti-raid state: when Discord last saw an incident, and how long the mitigations run for. */
data class GuildIncidentsData(
    val raidDetectedAt: UtcDateTime? = null,
    val dmSpamDetectedAt: UtcDateTime? = null,
    val invitesDisabledUntil: UtcDateTime? = null,
    val dmsDisabledUntil: UtcDateTime? = null,
)
