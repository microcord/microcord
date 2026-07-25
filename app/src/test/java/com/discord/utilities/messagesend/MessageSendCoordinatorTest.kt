package com.discord.utilities.messagesend

import com.discord.api.message.MessageReference
import com.discord.models.message.Message
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StorePendingReplies
import java.lang.reflect.Proxy
import java.util.PriorityQueue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import rx.Observable

class MessageSendCoordinatorTest {
    @Test fun validatesBeforeQueueing() {
        val harness = Harness()
        assertTrue(harness.coordinator.send(MessageSendCoordinator.Send(1, content = "")) is MessageSendCoordinator.Submission.Rejected)
        assertTrue(harness.coordinator.send(MessageSendCoordinator.Send(1, content = "x", canSend = false)) is MessageSendCoordinator.Submission.Rejected)
        assertTrue(harness.coordinator.send(MessageSendCoordinator.Send(1, content = "x", communicationDisabled = true)) is MessageSendCoordinator.Submission.Rejected)
        assertTrue(harness.coordinator.send(MessageSendCoordinator.Send(1, content = "x", systemDm = true)) is MessageSendCoordinator.Submission.Rejected)
        assertTrue(harness.coordinator.send(MessageSendCoordinator.Send(1, content = "x".repeat(2001))) is MessageSendCoordinator.Submission.Rejected)
        assertTrue(harness.calls.isEmpty())
    }

    @Test fun emitsOptimisticThenReconcilesByNonce() {
        val harness = Harness()
        val submission = harness.coordinator.send(MessageSendCoordinator.Send(7, 8, "hello")) as MessageSendCoordinator.Submission.Accepted
        assertEquals(-1, submission.optimistic.type)
        assertEquals(submission.requestId, submission.optimistic.nonce)

        harness.runCurrent()
        val reconciled = harness.updates.filterIsInstance<MessageSendCoordinator.Update.Reconciled>().single()
        assertEquals(99, reconciled.message.id)
        assertEquals(submission.requestId, harness.bodies.single().nonce)
    }

    @Test fun failureTypesAndRetryTransition() {
        val harness = Harness()
        harness.failures += http(400, "{\"captcha_key\":[\"required\"]}")
        harness.failures += http(400, "{\"code\":200000,\"message\":\"blocked\"}")
        harness.failures += http(500, "{}")

        val captcha = harness.sendAndRun("captcha")
        val automod = harness.sendAndRun("automod")
        harness.sendAndRun("other")
        assertEquals(listOf(-6, -8, -2), harness.updates.filterIsInstance<MessageSendCoordinator.Update.Failed>().map { it.message.type })

        assertTrue(harness.coordinator.retry(captcha.requestId, com.discord.utilities.captcha.CaptchaHelper.CaptchaPayload("solved", "rq")))
        assertEquals(-1, (harness.updates.last() as MessageSendCoordinator.Update.Optimistic).message.type)
        harness.coordinator.cancel(automod.requestId)
        harness.runCurrent()
        assertTrue(harness.updates.any { it is MessageSendCoordinator.Update.Cancelled && it.requestId == automod.requestId })
    }

    @Test fun replyAndEditUseExpectedPayloads() {
        val harness = Harness()
        val original = Message(id = 12, channelId = 7, guildId = 8, content = "old")
        val reply = StorePendingReplies.PendingReply(MessageReference(8, 7, 12), original, shouldMention = false)
        harness.coordinator.send(MessageSendCoordinator.Send(
            7, 8, "reply", reply = reply,
            allowedMentions = RestAPIParams.Message.AllowedMentions(users = listOf(5)),
        ))
        harness.runCurrent()

        assertEquals(12L, harness.bodies[0].messageReference?.messageId)
        assertEquals(false, harness.bodies[0].allowedMentions?.repliedUser)
        assertEquals(listOf(5L), harness.bodies[0].allowedMentions?.users)

        harness.coordinator.edit(original, "edited")
        harness.runCurrent()
        assertEquals("editMessage", harness.calls.last())
        assertEquals("edited", harness.bodies.last().content)
    }

    @Test fun typingIsEmptySafeAndThrottled() {
        val harness = Harness()
        harness.coordinator.typing(7, "")
        harness.coordinator.typing(7, "a")
        harness.coordinator.typing(7, "ab")
        assertEquals(listOf("setUserTyping"), harness.calls)
        harness.time += 9_000
        harness.coordinator.typing(7, "abc")
        assertEquals(2, harness.calls.size)
    }

    private class Harness {
        var time = 1_000L
        val scheduler = FakeScheduler { time }
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<RestAPIParams.Message>()
        val failures = ArrayDeque<Throwable>()
        val updates = mutableListOf<MessageSendCoordinator.Update>()
        val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, args ->
            calls += method.name
            (args?.lastOrNull() as? RestAPIParams.Message)?.let(bodies::add)
            if (method.name == "setUserTyping") Observable.empty<Void>()
            else failures.removeFirstOrNull()?.let { Observable.error<com.discord.api.message.Message>(it) }
                ?: Observable.just(com.discord.api.message.Message(99, args!![0] as Long, content = bodies.last().content, nonce = bodies.last().nonce))
        } as RestAPIInterface
        val coordinator = MessageSendCoordinator(api, { time }, NonceGenerator { time }, scheduler).also {
            it.observeUpdates().subscribe(updates::add)
        }

        fun runCurrent() = scheduler.runCurrent()
        fun sendAndRun(content: String) = (coordinator.send(MessageSendCoordinator.Send(7, content = content)) as MessageSendCoordinator.Submission.Accepted).also { runCurrent() }
    }

    private class FakeScheduler(private val clock: () -> Long) : MessageQueueScheduler {
        private data class Task(val at: Long, val order: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = PriorityQueue(compareBy<Task>({ it.at }, { it.order }))
        private var order = 0L
        override fun now() = clock()
        override fun schedule(delayMs: Long, action: () -> Unit): MessageQueueCancellable {
            val task = Task(now() + delayMs, order++, action)
            tasks += task
            return MessageQueueCancellable { task.cancelled = true }
        }
        fun runCurrent() {
            while (tasks.isNotEmpty() && tasks.peek()!!.at <= now()) tasks.remove().takeUnless { it.cancelled }?.action?.invoke()
        }
    }

    private companion object {
        fun http(status: Int, body: String) = HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))
    }
}
