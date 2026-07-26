package com.discord.analytics.generated.traits

import com.discord.api.science.AnalyticsSchema

/**
 * Channel snapshot attached to a schema-backed event.
 *
 * The same six values are produced in map form by
 * `StoreAnalytics.getChannelSnapshotAnalyticsProperties`.
 */
data class TrackChannel(
    val channelId: Long? = null,
    val channelType: Long? = null,
    val channelSizeTotal: Long? = null,
    val channelSizeOnline: Long? = null,
    val channelMemberPerms: Long? = null,
    val channelHidden: Boolean? = null,
)

/** Implemented by generated schemas that carry a channel snapshot. */
interface TrackChannelReceiver : AnalyticsSchema {
    val trackChannel: TrackChannel?
}
