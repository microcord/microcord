package com.discord.api.science

/**
 * Marker contract for a science/analytics event payload.
 *
 * The reference declares a single accessor returning the schema's wire type name, which the
 * tracker uses as the analytics event name.
 */
interface AnalyticsSchema {
    val analyticsSchemaTypeName: String
}
