package com.discord.analytics.generated.traits

import com.discord.api.science.AnalyticsSchema

/** Grouping/sequencing information for an `impression_*` schema. */
data class TrackImpressionMetadata(
    val sequenceId: CharSequence? = null,
    val impressionName: CharSequence? = null,
    val impressionType: CharSequence? = null,
    val impressionGroup: CharSequence? = null,
)

/** Implemented by every generated `impression_*` schema. */
interface TrackImpressionMetadataReceiver : AnalyticsSchema {
    val trackImpressionMetadata: TrackImpressionMetadata?
}
