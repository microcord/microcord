package com.discord.utilities.frecency

import com.discord.utilities.applicationcommands.ApplicationCommandFrecencyTracker
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.Observable

class FrecencyTrackerPersistenceTest {
    private val directory = Files.createTempDirectory("frecency").toFile()

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun survivesAPersistedRoundTripAfterScoring() {
        val now = 1_700_000_000_000L
        val tracker = ApplicationCommandFrecencyTracker()
        tracker.track("once", now)
        repeat(3) { tracker.track("often:1", now - it) }
        tracker.restore(tracker.toEntries(10, now) + ("synced" to FrecencyTracker.Entry(1, listOf(now))))
        val sorted = tracker.getSortedKeys(now).toList()

        Persister.init(directory, Observable.never())
        Persister("tracker", ApplicationCommandFrecencyTracker()).set(tracker, persist = true)
        Persister.init(directory, Observable.never())
        val restored = Persister("tracker", ApplicationCommandFrecencyTracker()).get()

        assertEquals(sorted, restored.getSortedKeys(now).toList())
        assertEquals(tracker.toEntries(10, now), restored.toEntries(10, now))
    }
}
