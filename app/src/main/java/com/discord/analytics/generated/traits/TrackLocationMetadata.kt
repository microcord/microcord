package com.discord.analytics.generated.traits

import com.discord.api.science.AnalyticsSchema

/**
 * Where in the app a schema-backed event was raised.
 *
 * This is the schema-side counterpart of [com.discord.utilities.analytics.Traits.Location], which
 * serializes the same five values into a flat property map for map-backed events.
 */
data class TrackLocationMetadata(
    val locationPage: CharSequence? = null,
    val locationSection: CharSequence? = null,
    val locationObject: CharSequence? = null,
    val locationObjectType: CharSequence? = null,
    val locationPromotionId: Long? = null,
)

/** Implemented by generated schemas that carry a location. */
interface TrackLocationMetadataReceiver : AnalyticsSchema {
    val trackLocationMetadata: TrackLocationMetadata?
}
