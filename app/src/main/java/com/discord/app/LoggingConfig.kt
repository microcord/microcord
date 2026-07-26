package com.discord.app

import com.discord.api.science.AnalyticsSchema
import kotlin.reflect.KProperty1

/**
 * Declares how a screen reports impressions.
 *
 * @param autoLogImpressionOnChanged when `true`, a repeat visit re-logs the impression as soon as
 *   the schema changed. When `false`, only the first impression of a screen instance is logged.
 * @param autoLogImpressionProperties narrows change detection to the listed schema properties.
 *   When empty, whole-schema equality is used.
 * @param impressionSchemaProvider produces the schema for the current screen state, or `null` when
 *   the screen is not ready to report yet.
 */
data class LoggingConfig(
    val autoLogImpressionOnChanged: Boolean = true,
    val autoLogImpressionProperties: List<KProperty1<*, *>> = emptyList(),
    val impressionSchemaProvider: (() -> AnalyticsSchema?)? = null,
)
