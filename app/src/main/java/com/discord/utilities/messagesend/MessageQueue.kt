package com.discord.utilities.messagesend

import java.util.ArrayDeque
import rx.Single
import rx.Subscription

class MessageQueue(
    private val sender: (MessageRequest) -> Single<MessageResult>,
    private val scheduler: MessageQueueScheduler = ExecutorMessageQueueScheduler(),
) {
    private val queue = ArrayDeque<MessageRequest>()
    private var current: MessageRequest? = null
    private var sendSubscription: Subscription? = null
    private var retry: MessageQueueCancellable? = null
    private var retryIsNetwork = false
    private var networkRetryMs = NETWORK_INITIAL_RETRY_MS
    private var attempt = 0L

    fun enqueue(request: MessageRequest) {
        scheduler.schedule { queue.addLast(request); drain() }
    }

    fun cancel(requestId: String) {
        scheduler.schedule {
            val active = current
            if (active?.requestId == requestId) {
                finish(active, MessageResult.UserCancelled)
                return@schedule
            }

            val queued = queue.firstOrNull { it.requestId == requestId } ?: return@schedule
            queue.remove(queued)
            queued.onCompleted(MessageResult.UserCancelled, queue.isEmpty() && current == null)
        }
    }

    fun handleConnected() {
        scheduler.schedule {
            if (current != null && retryIsNetwork && retry != null) {
                retry?.cancel()
                retry = null
                retryIsNetwork = false
                sendCurrent()
            } else {
                drain()
            }
        }
    }

    fun cancelAll() {
        scheduler.schedule {
            retry?.cancel()
            retry = null
            sendSubscription?.unsubscribe()
            sendSubscription = null
            current?.let { active ->
                current = null
                active.onCompleted(MessageResult.UserCancelled, queue.isEmpty())
            }
            while (queue.isNotEmpty()) {
                queue.removeFirst().onCompleted(MessageResult.UserCancelled, queue.isEmpty())
            }
        }
    }

    private fun drain() {
        if (current != null) return
        while (queue.isNotEmpty()) {
            val request = queue.removeFirst()
            if (scheduler.now() - request.attemptTimestamp >= MESSAGE_TIMEOUT_MS) {
                request.onCompleted(MessageResult.Timeout, queue.isEmpty())
                networkRetryMs = NETWORK_INITIAL_RETRY_MS
            } else {
                current = request
                sendCurrent()
                return
            }
        }
    }

    private fun sendCurrent() {
        val request = current ?: return
        if (scheduler.now() - request.attemptTimestamp >= MESSAGE_TIMEOUT_MS) {
            finish(request, MessageResult.Timeout)
            return
        }
        retry = null
        val thisAttempt = ++attempt
        sendSubscription = try {
            sender(request).subscribe(
                { result -> scheduler.schedule { handleResult(request, thisAttempt, result) } },
                { error -> scheduler.schedule { handleResult(request, thisAttempt, MessageResult.UnknownFailure(error)) } },
            )
        } catch (error: Throwable) {
            scheduler.schedule { handleResult(request, thisAttempt, MessageResult.UnknownFailure(error)) }
            null
        }
    }

    private fun handleResult(request: MessageRequest, thisAttempt: Long, result: MessageResult) {
        if (current !== request || attempt != thisAttempt) return
        sendSubscription = null
        when (result) {
            is MessageResult.RateLimited -> scheduleRetry(result.retryAfterMs, false)
            MessageResult.NetworkFailure -> {
                val delay = networkRetryMs
                networkRetryMs = (networkRetryMs * 2).coerceAtMost(MAX_NETWORK_RETRY_MS)
                scheduleRetry(delay, true)
            }
            else -> finish(request, result)
        }
    }

    private fun scheduleRetry(delayMs: Long, network: Boolean) {
        retryIsNetwork = network
        retry = scheduler.schedule(delayMs.coerceAtLeast(0)) {
            retry = null
            retryIsNetwork = false
            if (current != null) sendCurrent()
        }
    }

    private fun finish(request: MessageRequest, result: MessageResult) {
        if (current !== request) return
        attempt++
        sendSubscription?.unsubscribe()
        sendSubscription = null
        retry?.cancel()
        retry = null
        retryIsNetwork = false
        current = null
        networkRetryMs = NETWORK_INITIAL_RETRY_MS
        request.onCompleted(result, queue.isEmpty())
        drain()
    }

    private companion object {
        const val MESSAGE_TIMEOUT_MS = 60 * 60 * 1000L
        const val NETWORK_INITIAL_RETRY_MS = 5_000L
        const val MAX_NETWORK_RETRY_MS = MESSAGE_TIMEOUT_MS
    }
}
