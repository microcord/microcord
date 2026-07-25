package com.discord.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerParameters

class MessageAckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): ListenableWorker.Result {
        val channelId = inputData.getLong(KEY_CHANNEL_ID, -1)
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1)
        val generation = inputData.getInt(KEY_AUTH_GENERATION, -1)
        if (channelId <= 0 || messageId <= 0) return ListenableWorker.Result.failure()
        if (MessageWorkerActions.isCurrentAuthGeneration?.invoke(generation) != true) return ListenableWorker.Result.failure()
        val action = MessageWorkerActions.acknowledgeMessage ?: return ListenableWorker.Result.failure()
        return try {
            if (action(channelId, messageId)) ListenableWorker.Result.success() else retryOrFail()
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    private fun retryOrFail() = if (runAttemptCount < MAX_ATTEMPTS) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()

    companion object {
        const val KEY_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val KEY_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        const val KEY_AUTH_GENERATION = "auth_generation"
        const val MAX_ATTEMPTS = 5
    }
}
