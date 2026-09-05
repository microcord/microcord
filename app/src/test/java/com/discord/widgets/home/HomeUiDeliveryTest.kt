package com.discord.widgets.home

import com.discord.utilities.rx.ui
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Scheduler
import rx.Subscription
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.schedulers.TestScheduler
import rx.subjects.BehaviorSubject

class HomeUiDeliveryTest {
    private val main = TestScheduler()

    @Before fun setUp() {
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(object : RxAndroidSchedulersHook() {
            override fun getMainThreadScheduler(): Scheduler = main
        })
    }

    @After fun tearDown() = RxAndroidPlugins.getInstance().reset()

    @Test fun stopCancelsQueuedHomeUpdatesAndRestartObservesOnlyCurrentState() {
        val home = WidgetHome()
        val state = BehaviorSubject.create(0)
        val slots = WidgetHome::class.java.declaredFields.filter { it.type == Subscription::class.java }
        assertTrue(slots.any { it.name == "mentionSubscription" })
        var staleDeliveries = 0
        val subscriptions = slots.map { slot ->
            slot.isAccessible = true
            state.ui().subscribe {
                staleDeliveries++
                home.childFragmentManager // The reported crash if work escapes cancellation after detach.
            }.also { slot.set(home, it) }
        }
        try {
            state.onNext(1)
            assertEquals(0, staleDeliveries)
            home.onStop()
            main.triggerActions()
            assertTrue(subscriptions.all(Subscription::isUnsubscribed))
            slots.forEach { assertNull(it.get(home)) }
            assertEquals(0, staleDeliveries)
            assertFalse(state.hasObservers())

            state.onNext(2)
            val received = slots.associate { it.name to mutableListOf<Int>() }
            slots.forEach { slot ->
                slot.set(home, state.ui().subscribe { received.getValue(slot.name).add(it) })
            }
            main.triggerActions()
            received.values.forEach { assertEquals(listOf(2), it) }

            state.onNext(3)
            home.onStop()
            main.triggerActions()
            received.values.forEach { assertEquals(listOf(2), it) }
            assertFalse(state.hasObservers())
        } finally {
            home.onStop()
        }
    }
}
