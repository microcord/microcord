package com.discord.utilities.notifications

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerParameters

class NotificationMarkReadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): ListenableWorker.Result {
        val channelId = inputData.getLong(NotificationActionReceiver.EXTRA_CHANNEL_ID, -1)
        val messageId = inputData.getLong(NotificationActionReceiver.EXTRA_MESSAGE_ID, -1)
        val generation = inputData.getInt(NotificationActionReceiver.EXTRA_AUTH_GENERATION, -1)
        val actions = NotificationActionReceiver.callbacks ?: return Result.failure()
        if (channelId <= 0 || messageId <= 0 || !actions.isCurrentAuthGeneration(generation)) return Result.failure()
        return runCatching { if (actions.markRead(channelId, messageId)) Result.success() else retryOrFail() }
            .getOrElse { retryOrFail() }
    }

    private fun retryOrFail() = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
}

class NotificationReplyWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): ListenableWorker.Result {
        val channelId = inputData.getLong(NotificationActionReceiver.EXTRA_CHANNEL_ID, -1)
        val generation = inputData.getInt(NotificationActionReceiver.EXTRA_AUTH_GENERATION, -1)
        val reply = inputData.getString(NotificationActionReceiver.EXTRA_REPLY)
        val actions = NotificationActionReceiver.callbacks ?: return Result.failure()
        if (channelId <= 0 || reply.isNullOrBlank() || reply.length > 2_000 || !actions.isCurrentAuthGeneration(generation)) return Result.failure()
        return runCatching { if (actions.reply(channelId, reply)) Result.success() else retryOrFail() }
            .getOrElse { retryOrFail() }
    }

    private fun retryOrFail() = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
}

private const val MAX_ATTEMPTS = 5
