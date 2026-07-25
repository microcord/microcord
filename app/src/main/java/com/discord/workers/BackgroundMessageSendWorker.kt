package com.discord.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerParameters

class BackgroundMessageSendWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): ListenableWorker.Result {
        if (!inputData.getBoolean(KEY_CAN_REPLAY_ATTACHMENTS, true)) return ListenableWorker.Result.failure()
        val action = MessageWorkerActions.sendPendingMessages ?: return ListenableWorker.Result.failure()
        return try {
            if (action()) ListenableWorker.Result.success() else retryOrFail()
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    private fun retryOrFail() = if (runAttemptCount < MAX_ATTEMPTS) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()

    companion object {
        const val KEY_CAN_REPLAY_ATTACHMENTS = "can_replay_attachments"
        const val MAX_ATTEMPTS = 5
    }
}
