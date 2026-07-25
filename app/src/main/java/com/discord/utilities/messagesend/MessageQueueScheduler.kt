package com.discord.utilities.messagesend

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun interface MessageQueueCancellable {
    fun cancel()
}

interface MessageQueueScheduler {
    fun now(): Long
    fun schedule(delayMs: Long = 0, action: () -> Unit): MessageQueueCancellable
}

class ExecutorMessageQueueScheduler : MessageQueueScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MessageQueue").apply { isDaemon = true }
    }

    override fun now(): Long = System.currentTimeMillis()

    override fun schedule(delayMs: Long, action: () -> Unit): MessageQueueCancellable {
        val future = executor.schedule(action, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return MessageQueueCancellable { future.cancel(false) }
    }
}
