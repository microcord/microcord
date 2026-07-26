package com.discord.app

import com.discord.api.science.AnalyticsSchema
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the impression deduplication rules, which were reconstructed from bytecode because the
 * reference method does not decompile.
 */
class AppLoggerTest {

    private data class ScreenViewed(
        val screen: String,
        val guildId: Long,
    ) : AnalyticsSchema {
        override val analyticsSchemaTypeName: String get() = "screen_viewed"
    }

    private class Screen(var config: LoggingConfig?) : AppLogger.Provider {
        override val loggingConfig: LoggingConfig? get() = config
    }

    private fun loggerFor(screen: Screen, tracked: MutableList<AnalyticsSchema>) =
        AppLogger(screen, { schema -> tracked += schema }, isImpressionLoggingEnabled = true)

    @Test
    fun `first impression always logs`() {
        val tracked = mutableListOf<AnalyticsSchema>()
        val schema = ScreenViewed("home", 1L)
        val screen = Screen(LoggingConfig(impressionSchemaProvider = { schema }))

        loggerFor(screen, tracked).logImpression()

        assertEquals(listOf(schema), tracked)
    }

    @Test
    fun `an unchanged schema is not logged twice`() {
        val tracked = mutableListOf<AnalyticsSchema>()
        val screen = Screen(LoggingConfig(impressionSchemaProvider = { ScreenViewed("home", 1L) }))
        val logger = loggerFor(screen, tracked)

        logger.logImpression()
        logger.logImpression()

        assertEquals(1, tracked.size)
    }

    @Test
    fun `a changed schema is logged again when auto logging on change is enabled`() {
        val tracked = mutableListOf<AnalyticsSchema>()
        var guildId = 1L
        val screen = Screen(LoggingConfig(impressionSchemaProvider = { ScreenViewed("home", guildId) }))
        val logger = loggerFor(screen, tracked)

        logger.logImpression()
        guildId = 2L
        logger.logImpression()

        assertEquals(listOf(ScreenViewed("home", 1L), ScreenViewed("home", 2L)), tracked)
    }

    @Test
    fun `a changed schema is ignored when auto logging on change is disabled`() {
        val tracked = mutableListOf<AnalyticsSchema>()
        var guildId = 1L
        val screen = Screen(
            LoggingConfig(
                autoLogImpressionOnChanged = false,
                impressionSchemaProvider = { ScreenViewed("home", guildId) },
            ),
        )
        val logger = loggerFor(screen, tracked)

        logger.logImpression()
        guildId = 2L
        logger.logImpression()

        assertEquals(1, tracked.size)
    }

    @Test
    fun `only the declared properties drive change detection`() {
        val tracked = mutableListOf<AnalyticsSchema>()
        var screenName = "home"
        var guildId = 1L
        val screen = Screen(
            LoggingConfig(
                autoLogImpressionProperties = listOf(ScreenViewed::screen),
                impressionSchemaProvider = { ScreenViewed(screenName, guildId) },
            ),
        )
        val logger = loggerFor(screen, tracked)

        logger.logImpression()
        guildId = 2L
        logger.logImpression()
        assertEquals(1, tracked.size)

        screenName = "friends"
        logger.logImpression()
        assertEquals(2, tracked.size)
    }

    @Test
    fun `nothing is logged without a schema provider`() {
        val tracked = mutableListOf<AnalyticsSchema>()
        val screen = Screen(LoggingConfig())

        loggerFor(screen, tracked).logImpression()

        assertEquals(0, tracked.size)
    }

    @Test
    fun `nothing is logged when impression logging is disabled`() {
        val tracked = mutableListOf<AnalyticsSchema>()
        val screen = Screen(LoggingConfig(impressionSchemaProvider = { ScreenViewed("home", 1L) }))

        AppLogger(screen, { schema -> tracked += schema }, isImpressionLoggingEnabled = false).logImpression()

        assertEquals(0, tracked.size)
    }
}
