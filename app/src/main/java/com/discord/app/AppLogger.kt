package com.discord.app

import com.discord.api.science.AnalyticsSchema
import kotlin.reflect.KProperty1

/** Receives screen impression events. */
fun interface ImpressionTracker {
    fun track(schema: AnalyticsSchema)
}

/**
 * Screen impression logger owned by every [AppFragment] / [AppBottomSheet].
 *
 * `logImpression` is called on every `onViewBoundOrOnResume`; the deduplication rules in
 * [LoggingConfig] decide whether that turns into an actual tracked impression.
 */
class AppLogger(
    private val provider: Provider,
    private val tracker: ImpressionTracker = defaultTracker,
    private val isImpressionLoggingEnabled: Boolean = isEnabled,
) {

    /** Implemented by screens so the logger can read their (possibly changing) configuration. */
    interface Provider {
        val loggingConfig: LoggingConfig?
    }

    private var hasLoggedImpression: Boolean = false
    private var previousAnalyticsSchema: AnalyticsSchema? = null

    /**
     * Logs the current impression when the configuration allows it.
     *
     * The first impression of a screen instance always logs. Subsequent calls only log when
     * [LoggingConfig.autoLogImpressionOnChanged] is set and the schema changed, where "changed"
     * means whole-schema inequality, or inequality of one of
     * [LoggingConfig.autoLogImpressionProperties] when that list is non-empty.
     */
    @Synchronized
    fun logImpression() {
        if (!isImpressionLoggingEnabled) return
        val config = provider.loggingConfig ?: return
        val schema = config.impressionSchemaProvider?.invoke() ?: return
        val previous = previousAnalyticsSchema

        val shouldLog = previous == null ||
            (config.autoLogImpressionOnChanged && hasChanged(config.autoLogImpressionProperties, previous, schema))
        if (hasLoggedImpression && !shouldLog) return

        previousAnalyticsSchema = schema
        hasLoggedImpression = true
        track(schema)
    }

    /** Tracks [analyticsSchema], or the configured schema when no override is supplied. */
    fun track(analyticsSchema: AnalyticsSchema? = null) {
        val schema = analyticsSchema ?: provider.loggingConfig?.impressionSchemaProvider?.invoke() ?: return
        tracker.track(schema)
    }

    private fun hasChanged(
        properties: List<KProperty1<*, *>>,
        previous: AnalyticsSchema,
        current: AnalyticsSchema,
    ): Boolean {
        if (properties.isEmpty()) return previous != current
        return properties.any { property ->
            @Suppress("UNCHECKED_CAST")
            val typed = property as? KProperty1<AnalyticsSchema, Any?> ?: return@any false
            typed.get(current) != typed.get(previous)
        }
    }

    companion object {
        /**
         * Impression logging is a growth-team feature flag in the reference. Until the experiments
         * surface is reconstructed it stays on, since the default tracker only records the schema
         * type name.
         */
        var isEnabled: Boolean = true

        /**
         * Records only the schema type name. Impression payloads may embed user content, so nothing
         * beyond the type name is written to a log sink.
         */
        var defaultTracker: ImpressionTracker = ImpressionTracker { schema ->
            AppLog.recordBreadcrumb(schema.analyticsSchemaTypeName, "impression")
        }
    }
}
