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
        delay.observe().subscribe(states)

        delay.resumed()
        delay.paused()
        scheduler.advanceTimeBy(1_999, TimeUnit.MILLISECONDS)
        assertEquals(listOf(true, false), states.onNextEvents)
        delay.resumed()
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        assertEquals(listOf(true, false), states.onNextEvents)

        delay.paused()
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        assertEquals(listOf(true, false, true), states.onNextEvents)
    }
}
