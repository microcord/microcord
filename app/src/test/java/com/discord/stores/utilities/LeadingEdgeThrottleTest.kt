package com.discord.stores.utilities

import org.junit.Assert.assertEquals
import org.junit.Test
import rx.schedulers.TestScheduler
import rx.subjects.PublishSubject
import java.util.concurrent.TimeUnit

class LeadingEdgeThrottleTest {
    private val scheduler = TestScheduler()
    private val source = PublishSubject.create<Int>()
    private val seen = mutableListOf<Int>()

    private fun subscribe() {
        source.leadingEdgeThrottle(1, TimeUnit.SECONDS, scheduler).subscribe { seen += it }
    }

    @Test
    fun firstValueEmitsOnTheLeadingEdge() {
        subscribe()

        source.onNext(1)

        assertEquals(listOf(1), seen)
    }

    @Test
    fun burstCollapsesToTheNewestValueAtTheEndOfTheWindow() {
        subscribe()

        source.onNext(1)
        source.onNext(2)
        source.onNext(3)
        assertEquals("only the leading value is emitted inside the window", listOf(1), seen)

        scheduler.advanceTimeBy(999, TimeUnit.MILLISECONDS)
        assertEquals(listOf(1), seen)

        scheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        assertEquals(listOf(1, 3), seen)
    }

    @Test
    fun valueAfterTheWindowEmitsImmediatelyAgain() {
        subscribe()

        source.onNext(1)
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)

        source.onNext(2)

        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun completionFlushesThePendingValue() {
        subscribe()

        source.onNext(1)
        source.onNext(2)
        source.onCompleted()

        assertEquals(listOf(1, 2), seen)
    }
}
