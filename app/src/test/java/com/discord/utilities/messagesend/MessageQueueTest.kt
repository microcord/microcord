package com.discord.utilities.messagesend

import java.util.PriorityQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Single
import rx.SingleSubscriber

class MessageQueueTest {
    @Test
    fun fifoAndOneInFlight() {
        val harness = Harness()
        harness.enqueue("one")
        harness.enqueue("two")
        harness.runCurrent()

        assertEquals(listOf("one"), harness.started)
        harness.reply(MessageResult.Success)
        assertEquals(listOf("one", "two"), harness.started)
        harness.reply(MessageResult.Success)
        assertEquals(listOf("one" to MessageResult.Success, "two" to MessageResult.Success), harness.completed)
    }

    @Test
    fun cancelsQueuedAndInFlightExactlyOnce() {
        val harness = Harness()
        harness.enqueue("one")
        harness.enqueue("two")
        harness.runCurrent()

        harness.queue.cancel("two")
        harness.queue.cancel("one")
        harness.runCurrent()
        harness.reply(MessageResult.Success)

        assertEquals(1, harness.completed.count { it.first == "one" })
        assertEquals(1, harness.completed.count { it.first == "two" })
        assertTrue(harness.completed.all { it.second == MessageResult.UserCancelled })
    }

    @Test
    fun expiredRequestsTimeoutWithoutSending() {
        val harness = Harness()
        harness.scheduler.advanceBy(60 * 60 * 1000L)
        harness.enqueue("old", timestamp = 0)
        harness.runCurrent()

        assertTrue(harness.started.isEmpty())
        assertEquals(listOf("old" to MessageResult.Timeout), harness.completed)
    }

    @Test
    fun retryStopsWhenRequestExpires() {
        val harness = Harness()
        harness.enqueue("old", timestamp = 0)
        harness.runCurrent()
        harness.reply(MessageResult.RateLimited(60 * 60 * 1000L))
        harness.scheduler.advanceBy(60 * 60 * 1000L)

        assertEquals(listOf("old"), harness.started)
        assertEquals(listOf("old" to MessageResult.Timeout), harness.completed)
    }

    @Test
    fun rateLimitRetriesAtExactDelayBeforeNextRequest() {
        val harness = Harness()
        harness.enqueue("one")
        harness.enqueue("two")
        harness.runCurrent()
        harness.reply(MessageResult.RateLimited(1234))

        harness.scheduler.advanceBy(1233)
        assertEquals(listOf("one"), harness.started)
        harness.scheduler.advanceBy(1)
        assertEquals(listOf("one", "one"), harness.started)
        harness.reply(MessageResult.Success)
        assertEquals("two", harness.started.last())
    }

    @Test
    fun reconnectCancelsNetworkBackoffAndDrains() {
        val harness = Harness()
        harness.enqueue("one")
        harness.runCurrent()
        harness.reply(MessageResult.NetworkFailure)

        harness.scheduler.advanceBy(4999)
        assertEquals(1, harness.started.size)
        harness.queue.handleConnected()
        harness.runCurrent()
        assertEquals(2, harness.started.size)
        harness.scheduler.advanceBy(1)
        assertEquals(2, harness.started.size)
    }

    @Test
    fun captchaAndAutoModAreTerminal() {
        val harness = Harness()
        harness.enqueue("captcha")
        harness.enqueue("automod")
        harness.runCurrent()
        harness.reply(MessageResult.CaptchaRequired(null, null))
        harness.reply(MessageResult.AutoModBlock)

        assertEquals(
            listOf("captcha" to MessageResult.CaptchaRequired(null, null), "automod" to MessageResult.AutoModBlock),
            harness.completed,
        )
        assertEquals(listOf("captcha", "automod"), harness.started)
    }

    @Test
    fun networkFailuresBackOffExponentially() {
        val harness = Harness()
        harness.enqueue("one")
        harness.runCurrent()
        harness.reply(MessageResult.NetworkFailure)
        harness.scheduler.advanceBy(5000)
        harness.reply(MessageResult.NetworkFailure)
        harness.scheduler.advanceBy(9999)
        assertEquals(2, harness.started.size)
        harness.scheduler.advanceBy(1)
        assertEquals(3, harness.started.size)
    }

    @Test
    fun nonceIsMonotonicWhenClockDoesNotAdvance() {
        val generator = NonceGenerator { 10L }
        val first = generator.nonce()
        assertEquals(first + 1, generator.nonce())
    }

    private class Harness {
        val scheduler = FakeScheduler()
        val started = mutableListOf<String>()
        val completed = mutableListOf<Pair<String, MessageResult>>()
        private val subscribers = ArrayDeque<SingleSubscriber<in MessageResult>>()
        val queue = MessageQueue(
            sender = { request ->
                Single.create { subscriber ->
                    started += request.requestId
                    subscribers.addLast(subscriber)
                }
            },
            scheduler = scheduler,
        )

        fun enqueue(id: String, timestamp: Long = scheduler.now()) {
            queue.enqueue(MessageRequest.Send(id, timestamp) { result, _ -> completed += id to result })
        }

        fun reply(result: MessageResult) {
            subscribers.removeFirst().onSuccess(result)
            runCurrent()
        }

        fun runCurrent() = scheduler.advanceBy(0)
    }

    private class FakeScheduler : MessageQueueScheduler {
        private data class Task(val at: Long, val order: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = PriorityQueue(compareBy<Task>({ it.at }, { it.order }))
        private var order = 0L
        private var time = 0L

        override fun now(): Long = time

        override fun schedule(delayMs: Long, action: () -> Unit): MessageQueueCancellable {
            val task = Task(time + delayMs, order++, action)
            tasks += task
            return MessageQueueCancellable { task.cancelled = true }
        }

        fun advanceBy(milliseconds: Long) {
            val target = time + milliseconds
            while (tasks.isNotEmpty() && tasks.peek().at <= target) {
                val task = tasks.remove()
                time = task.at
                if (!task.cancelled) task.action()
            }
            time = target
        }
    }
}
