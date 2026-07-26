package com.discord.analytics.generated.traits

import com.discord.api.science.AnalyticsSchema

/**
 * Guild snapshot attached to a schema-backed event.
 *
 * [isMember] and [isOwner] are not supplied by the call site: the tracker fills them in from the
 * store graph immediately before delivery (see
 * `com.discord.utilities.analytics.fillFromStores`), which is why the reference's generated schemas
 * expose `trackGuild` as a settable `var` while every other trait is read-only.
 */
data class TrackGuild(
    val guildId: Long? = null,
    val guildSizeTotal: Long? = null,
    val guildMemberNumRoles: Long? = null,
    val guildMemberPerms: Long? = null,
    val guildNumChannels: Long? = null,
    val guildNumTextChannels: Long? = null,
    val guildNumVoiceChannels: Long? = null,
    val guildNumRoles: Long? = null,
    val guildIsVip: Boolean? = null,
    val isMember: Boolean? = null,
    val numVoiceChannelsActive: Long? = null,
    val isOwner: Boolean? = null,
)

/** Implemented by generated schemas that carry a guild snapshot. */
interface TrackGuildReceiver : AnalyticsSchema {
    var trackGuild: TrackGuild?
}
