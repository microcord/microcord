package com.discord.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.subjects.PublishSubject
import rx.subjects.Subject

/**
 * The subscription contract every screen relies on: work bound to a component stops as soon as the
 * component emits its unsubscribe signal, which [AppFragment], [AppDialog] and [AppBottomSheet] do
 * from `onPause`.
 */
class AppComponentSubscriptionTest {

    private class FakeComponent : AppComponent {
        override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()

        /** Mirrors what the base classes do in `onPause`. */
        fun pause() = unsubscribeSignal.onNext(null)
    }

    @Test
    fun `bound subscription stops receiving after the component pauses`() {
        val component = FakeComponent()
        val source = PublishSubject.create<Int>()
        val received = mutableListOf<Int>()

        val subscription = source.bindToComponentLifecycle(component).subscribe { received += it }

        source.onNext(1)
        source.onNext(2)
        assertEquals(listOf(1, 2), received)

        component.pause()

        source.onNext(3)
        assertEquals(listOf(1, 2), received)
        assertTrue(subscription.isUnsubscribed)
    }

    @Test
    fun `bound subscription completes on pause`() {
        val component = FakeComponent()
        val source = PublishSubject.create<Int>()
        var completed = false

        source.bindToComponentLifecycle(component).subscribe({ }, { }, { completed = true })

        assertFalse(completed)
        component.pause()
        assertTrue(completed)
    }

    @Test
    fun `each component only responds to its own signal`() {
        val paused = FakeComponent()
        val active = FakeComponent()
        val source = PublishSubject.create<Int>()
        val pausedValues = mutableListOf<Int>()
        val activeValues = mutableListOf<Int>()

        source.bindToComponentLifecycle(paused).subscribe { pausedValues += it }
        source.bindToComponentLifecycle(active).subscribe { activeValues += it }

        source.onNext(1)
        paused.pause()
        source.onNext(2)

        assertEquals(listOf(1), pausedValues)
        assertEquals(listOf(1, 2), activeValues)
    }

    @Test
    fun `a resumed component can bind again after pausing`() {
        val component = FakeComponent()
        val source = PublishSubject.create<Int>()
        val firstPass = mutableListOf<Int>()
        val secondPass = mutableListOf<Int>()

        source.bindToComponentLifecycle(component).subscribe { firstPass += it }
        source.onNext(1)
        component.pause()

        source.bindToComponentLifecycle(component).subscribe { secondPass += it }
        source.onNext(2)

        assertEquals(listOf(1), firstPass)
        assertEquals(listOf(2), secondPass)
    }
}
