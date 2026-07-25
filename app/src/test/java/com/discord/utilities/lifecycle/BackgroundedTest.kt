package com.discord.utilities.lifecycle

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.observers.TestSubscriber
import rx.schedulers.TestScheduler

class BackgroundedTest {
    @Test
    fun waitsTwoSecondsAndCancelsWhenResumed() {
        val scheduler = TestScheduler()
        val delay = BackgroundDelay(scheduler)
        val states = TestSubscriber<Boolean>()
        val activity = Any()
        delay.observe().subscribe(states)

        delay.resumed(activity)
        delay.paused(activity)
        scheduler.advanceTimeBy(1_999, TimeUnit.MILLISECONDS)
        assertEquals(listOf(true, false), states.onNextEvents)
        delay.resumed(activity)
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        assertEquals(listOf(true, false), states.onNextEvents)

        delay.paused(activity)
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        assertEquals(listOf(true, false, true), states.onNextEvents)
    }

    @Test
    fun remainsForegroundWhileAnotherActivityIsResumed() {
        val scheduler = TestScheduler()
        val delay = BackgroundDelay(scheduler)
        val states = TestSubscriber<Boolean>()
        val first = Any()
        val second = Any()
        delay.observe().subscribe(states)

        delay.resumed(first)
        delay.resumed(second)
        delay.paused(first)
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        assertEquals(listOf(true, false), states.onNextEvents)

        delay.paused(second)
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        assertEquals(listOf(true, false, true), states.onNextEvents)
    }

    @Test
    fun duplicateAndDestroyedCallbacksDoNotCorruptTheResumedCount() {
        val scheduler = TestScheduler()
        val delay = BackgroundDelay(scheduler)
        val states = TestSubscriber<Boolean>()
        val first = Any()
        val second = Any()
        delay.observe().subscribe(states)

        delay.resumed(first)
        delay.resumed(first)
        delay.resumed(second)
        delay.paused(first)
        delay.paused(first)
        delay.destroyed(second)
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS)

        assertEquals(listOf(true, false, true), states.onNextEvents)
    }
}
