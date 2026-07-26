package com.discord.utilities.networking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Growth and gating contract for [Backoff].
 *
 * The growth formula is taken from the DEX for `Backoff.fail`, which JADX renders incorrectly:
 * `current = min(current + (2 * current * jitter), maxBackoffMs)`. With jitter disabled that is a
 * tripling, and `minBackoffMs` is the seed rather than the first delay.
 */
class BackoffTest {

    private val scheduler = RecordingScheduler()

    @Test
    fun eachFailureTriplesTheDelayUntilTheCap() {
        val backoff = Backoff(minBackoffMs = 100, maxBackoffMs = 1_000, jitter = false, scheduler = scheduler)

        assertEquals(300L, backoff.fail())
        assertEquals(900L, backoff.fail())
        assertEquals(1_000L, backoff.fail())
        assertEquals(1_000L, backoff.fail())
    }

    @Test
    fun jitterKeepsTheDelayBetweenOneAndThreeTimesTheCurrentValue() {
        val backoff = Backoff(minBackoffMs = 1_000, maxBackoffMs = Long.MAX_VALUE, jitter = true, scheduler = scheduler)

        val delay = backoff.fail()

        assertTrue("expected 1000..3000 but was $delay", delay in 1_000..3_000)
    }

    @Test
    fun theFirstFailureArmsTheRetryAndLaterOnesDoNotArmASecond() {
        val backoff = Backoff(minBackoffMs = 100, maxBackoffMs = 10_000, jitter = false, scheduler = scheduler)
        var runs = 0

        backoff.fail { runs++ }
        backoff.fail { runs++ }

        assertTrue(backoff.isPending)
        assertEquals(listOf(300L), scheduler.delays)
        assertEquals(0, runs)
    }

    @Test
    fun failingWithoutACallbackStillGrowsTheDelayButArmsNothing() {
        val backoff = Backoff(minBackoffMs = 100, maxBackoffMs = 10_000, jitter = false, scheduler = scheduler)

        backoff.fail()

        assertFalse(backoff.isPending)
        assertTrue(scheduler.delays.isEmpty())
    }

    @Test
    fun theGateClearsBeforeTheCallbackRunsSoTheCallbackCanFailAgain() {
        val backoff = Backoff(minBackoffMs = 100, maxBackoffMs = 10_000, jitter = false, scheduler = scheduler)
        var pendingInsideCallback: Boolean? = null

        backoff.fail { pendingInsideCallback = backoff.isPending }
        scheduler.fire()

        assertEquals(false, pendingInsideCallback)
        assertFalse(backoff.isPending)
    }

    @Test
    fun cancelClearsThePendingGateButKeepsTheAccumulatedDelay() {
        val backoff = Backoff(minBackoffMs = 100, maxBackoffMs = 10_000, jitter = false, scheduler = scheduler)
        backoff.fail { }

        backoff.cancel()

        assertFalse(backoff.isPending)
        assertEquals(900L, backoff.fail())
    }

    @Test
    fun succeedClearsThePendingGateAndResetsTheDelay() {
        val backoff = Backoff(minBackoffMs = 100, maxBackoffMs = 10_000, jitter = false, scheduler = scheduler)
        backoff.fail { }
        backoff.fail { }

        backoff.succeed()

        assertFalse(backoff.isPending)
        assertEquals(300L, backoff.fail())
    }

    @Test
    fun theFailureThresholdIsCrossedOnlyAfterMoreFailuresThanAllowed() {
        val backoff = Backoff(minBackoffMs = 100, maxBackoffMs = 10_000, failureThreshold = 2, scheduler = scheduler)

        backoff.fail()
        backoff.fail()
        assertFalse(backoff.hasReachedFailureThreshold())

        backoff.fail()
        assertTrue(backoff.hasReachedFailureThreshold())

        backoff.succeed()
        assertFalse(backoff.hasReachedFailureThreshold())
    }

    private class RecordingScheduler : Backoff.Scheduler {
        val delays = mutableListOf<Long>()
        private var action: (() -> Unit)? = null

        override fun schedule(action: () -> Unit, delayMs: Long) {
            delays += delayMs
            this.action = action
        }

        override fun cancel() {
            action = null
        }

        fun fire() {
            val armed = checkNotNull(action)
            action = null
            armed()
        }
    }
}
