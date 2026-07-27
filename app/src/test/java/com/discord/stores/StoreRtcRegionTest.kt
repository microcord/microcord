package com.discord.stores

import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.Observable
import rx.schedulers.TestScheduler

class StoreRtcRegionTest {

    @Test
    fun latencyTestRunsForEmptyChangedOrExpiredResultsOnly() {
        var now = 1_000L
        val store = StoreRtcRegion(
            dispatcher = Dispatcher(TestScheduler()),
            clock = Clock { now },
            fetchRegionsWithIps = { Observable.just(emptyList()) },
            hasNativeEngineEverInitialized = { true },
            rankRegionsByLatency = { _, _ -> },
        )
        val shouldTest = StoreRtcRegion::class.java.getDeclaredMethod(
            "shouldPerformLatencyTest",
            List::class.java,
            Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        val lastResult = StoreRtcRegion::class.java.getDeclaredField("lastTestResult")
            .apply { isAccessible = true }

        assertTrue(shouldTest.invoke(store, listOf("us-west"), now) as Boolean)

        lastResult.set(store, RtcLatencyTestResult(listOf("us-west"), listOf("us-west"), now))
        assertFalse(shouldTest.invoke(store, listOf("us-west"), now) as Boolean)
        assertTrue(shouldTest.invoke(store, listOf("us-east"), now) as Boolean)

        now += 86_400_000L
        assertTrue(shouldTest.invoke(store, listOf("us-west"), now) as Boolean)
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun initializePersister() {
            Persister.init(Files.createTempDirectory("rtc-region-test").toFile())
        }
    }
}
