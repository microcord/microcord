package com.discord.stores.utilities

import com.discord.utilities.time.Clock
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchManagerTest {
    private var now = 0L
    private val clock = Clock { now }
    private val batchManager = BatchManager(Dispatchers.Unconfined, clock)

    @After
    fun tearDown() = batchManager.dispose()

    /**
     * The debounce timer is deliberately far longer than any test takes, so every emission
     * below is caused by an explicit flush or by the max-delay rule rather than by wall time.
     */
    private fun batched(type: String, maxDebounceDelayMs: Long = NEVER_MS) =
        batchManager.createBatched<String>(type, NEVER_MS, maxDebounceDelayMs)

    @Test
    fun queuedValuesAreCoalescedIntoOneBatch() {
        val batched = batched(VOICE_STATE_UPDATE)
        val batches = batched.collect()

        batched.onNext("a")
        batched.onNext("b")
        batched.onNext("c")
        assertEquals("values stay queued until the debounce window closes", emptyList<List<String>>(), batches)

        batched.flush()

        assertEquals(listOf(listOf("a", "b", "c")), batches)
    }

    @Test
    fun flushRestartsTheQueue() {
        val batched = batched(VOICE_STATE_UPDATE)
        val batches = batched.collect()

        batched.onNext("a")
        batched.flush()
        batched.onNext("b")
        batched.flush()

        assertEquals(listOf(listOf("a"), listOf("b")), batches)
    }

    @Test
    fun exceedingTheMaxDebounceDelayFlushesImmediately() {
        val batched = batched(VOICE_STATE_UPDATE, maxDebounceDelayMs = 300)
        val batches = batched.collect()

        batched.onNext("a")
        now = 299
        batched.onNext("b")
        assertEquals(emptyList<List<String>>(), batches)

        now = 300
        batched.onNext("c")

        assertEquals(
            "a sustained burst still makes progress once the queue is old enough",
            listOf(listOf("a", "b", "c")),
            batches,
        )
    }

    @Test
    fun flushBatchesSkipsTheIncomingType() {
        val voiceStates = batched(VOICE_STATE_UPDATE)
        val presences = batched(PRESENCE_UPDATE)
        val voiceBatches = voiceStates.collect()
        val presenceBatches = presences.collect()

        voiceStates.onNext("voice")
        presences.onNext("presence")

        batchManager.flushBatches(VOICE_STATE_UPDATE)

        assertEquals("the incoming type keeps accumulating", emptyList<List<String>>(), voiceBatches)
        assertEquals("every other type is drained first", listOf(listOf("presence")), presenceBatches)
    }

    @Test
    fun flushBatchesDrainsEveryTypeWhenNothingIsIncoming() {
        val voiceStates = batched(VOICE_STATE_UPDATE)
        val presences = batched(PRESENCE_UPDATE)
        val voiceBatches = voiceStates.collect()
        val presenceBatches = presences.collect()

        voiceStates.onNext("voice")
        presences.onNext("presence")

        batchManager.flushBatches(null)

        assertEquals(listOf(listOf("voice")), voiceBatches)
        assertEquals(listOf(listOf("presence")), presenceBatches)
    }

    private fun <T> Batched<T>.collect(): List<List<T>> = mutableListOf<List<T>>().also { collected ->
        observe().subscribe { collected += it }
    }

    private companion object {
        private const val VOICE_STATE_UPDATE = "VOICE_STATE_UPDATE"
        private const val PRESENCE_UPDATE = "PRESENCE_UPDATE"
        private const val NEVER_MS = 10 * 60 * 1000L
    }
}
