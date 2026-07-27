package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.accessibility.AccessibilityFeatureFlags
import com.discord.utilities.accessibility.AccessibilityMonitor
import com.discord.utilities.accessibility.AccessibilityState
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreAccessibilitySuperPropertiesTest {
    @Before
    fun setUp() {
        Persister.init(Files.createTempDirectory("accessibility-super-properties").toFile())
    }

    @Test
    fun `snapshot publishes filtered screenreader and cumulative font flags`() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val monitor = AccessibilityMonitor().apply {
            update(
                AccessibilityState(
                    EnumSet.of(
                        AccessibilityFeatureFlags.SCREENREADER,
                        AccessibilityFeatureFlags.CHAT_FONT_SCALE_DECREASED,
                    ),
                ),
            )
        }
        val store = StoreAccessibility(dispatcher, ObservationDeck(), monitor)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), ObservationDeck()))

        store.init(testContext())
        dispatcher.schedule {
            store.updateDetectionAllowed(true)
            store.updateFontScale(125)
        }
        scheduler.triggerActions()

        assertEquals(true, AnalyticSuperProperties.superProperties["accessibility_support_enabled"])
        assertEquals(
            AccessibilityFeatureFlags.SCREENREADER.value or
                AccessibilityFeatureFlags.CHAT_FONT_SCALE_INCREASED.value or
                AccessibilityFeatureFlags.CHAT_FONT_SCALE_DECREASED.value,
            AnalyticSuperProperties.superProperties["accessibility_features"],
        )
    }
}
