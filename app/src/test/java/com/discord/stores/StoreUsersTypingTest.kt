package com.discord.stores

import com.discord.api.user.TypingUser
import java.util.PriorityQueue
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreUsersTypingTest {
    @Test
    fun typingTimersRestartAndSnapshotsStayDetached() {
        val dispatchScheduler = TestScheduler()
        val dispatcher = Dispatcher(dispatchScheduler)
        val deck = com.discord.stores.updates.ObservationDeck()
        val timer = FakeTypingScheduler()
        val store = StoreUsersTyping(dispatcher, deck, { 1L }, timer)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val channelSnapshots = mutableListOf<Set<Long>>()
        val multiSnapshots = mutableListOf<Map<Long, Set<Long>>>()
        store.observeTypingUsers(10).subscribe(channelSnapshots::add)
        store.observeTypingUsersForChannels(setOf(10, 20)).subscribe(multiSnapshots::add)

        dispatcher.schedule {
            store.handleTypingStart(typing(10, 1))
            store.handleTypingStart(typing(10, 2))
        }
        dispatchScheduler.triggerActions()
        val detached = channelSnapshots.last()

        timer.advanceBy(5_000)
        dispatcher.schedule {
            store.handleTypingStart(typing(10, 2))
            store.handleTypingStart(typing(20, 3))
        }
        dispatchScheduler.triggerActions()
        assertEquals(setOf(2L), detached)
        assertEquals(mapOf(10L to setOf(2L), 20L to setOf(3L)), multiSnapshots.last())

        timer.advanceBy(5_000)
        dispatchScheduler.triggerActions()
        assertEquals(setOf(2L), channelSnapshots.last())

        dispatcher.schedule { store.handleMessageCreate(10, 2) }
        dispatchScheduler.triggerActions()
        assertEquals(emptySet<Long>(), channelSnapshots.last())

        timer.advanceBy(5_000)
        dispatchScheduler.triggerActions()
        assertEquals(emptyMap<Long, Set<Long>>(), multiSnapshots.last())

        dispatcher.schedule {
            store.handleTypingStart(typing(10, 4))
            store.reset()
        }
        dispatchScheduler.triggerActions()
        assertEquals(emptyMap<Long, Set<Long>>(), multiSnapshots.last())
    }

    private fun typing(channelId: Long, userId: Long) = TypingUser(userId, 0, channelId, null)

    private class FakeTypingScheduler : TypingScheduler {
        private data class Task(
            val at: Long,
            val order: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val tasks = PriorityQueue(compareBy<Task>({ it.at }, { it.order }))
        private var now = 0L
        private var order = 0L

        override fun schedule(delayMs: Long, action: () -> Unit): TypingCancellable {
            val task = Task(now + delayMs, order++, action)
            tasks += task
            return TypingCancellable { task.cancelled = true }
        }

        fun advanceBy(milliseconds: Long) {
            val target = now + milliseconds
            while (tasks.isNotEmpty() && tasks.peek().at <= target) {
                val task = tasks.remove()
                now = task.at
                if (!task.cancelled) task.action()
            }
            now = target
        }
    }
}
