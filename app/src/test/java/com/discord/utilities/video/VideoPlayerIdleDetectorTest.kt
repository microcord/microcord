package com.discord.utilities.video

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.TestScheduler

class VideoPlayerIdleDetectorTest {
    private val timerScheduler = TestScheduler()
    private val callbackScheduler = TestScheduler()
    private val states = mutableListOf<Boolean>()
    private val detector = VideoPlayerIdleDetector(
        idleDetectionMs = 3_000L,
        idleDetectionScheduler = callbackScheduler,
        backgroundThreadScheduler = timerScheduler,
        onIdleStateChanged = states::add,
    )

    @Test
    fun becomesIdleOnlyAfterTheTimerFiresOnTheCallbackScheduler() {
        detector.beginIdleDetection()
        timerScheduler.advanceTimeBy(3, TimeUnit.SECONDS)

        assertFalse(detector.isIdle())
        callbackScheduler.triggerActions()

        assertTrue(detector.isIdle())
        assertTrue(states == listOf(true))
    }

    @Test
    fun interactionShowsIdleControlsAndRestartsTheirTimer() {
        detector.beginIdleDetection()
        timerScheduler.advanceTimeBy(3, TimeUnit.SECONDS)
        callbackScheduler.triggerActions()

        detector.onInteraction()

        assertFalse(detector.isIdle())
        assertTrue(states == listOf(true, false))
        timerScheduler.advanceTimeBy(3, TimeUnit.SECONDS)
        callbackScheduler.triggerActions()
        assertTrue(states == listOf(true, false, true))
    }

    @Test
    fun interactionWithAnActivePeerImmediatelyHidesThisDetector() {
        detector.beginIdleDetection()
        detector.onInteraction(otherIsIdle = false)

        assertTrue(detector.isIdle())
        assertTrue(states == listOf(true))
        timerScheduler.advanceTimeBy(3, TimeUnit.SECONDS)
        callbackScheduler.triggerActions()
        assertTrue(states == listOf(true))
    }

    @Test
    fun preventAndEndIdleDetectionCancelPendingTransitions() {
        detector.beginIdleDetection()
        detector.onPreventIdle()
        timerScheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        detector.endIdleDetection()
        timerScheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        callbackScheduler.triggerActions()

        assertFalse(detector.isIdle())
        assertTrue(states.isEmpty())
    }
}
