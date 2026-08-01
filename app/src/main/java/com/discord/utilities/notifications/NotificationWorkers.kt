package com.discord.utilities.notifications

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.discord.utilities.fcm.NotificationClient
import com.discord.workers.MessageWorkerActions

class NotificationMarkReadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): ListenableWorker.Result {
        val channelId = inputData.getLong(NotificationActionReceiver.EXTRA_CHANNEL_ID, -1)
        val messageId = inputData.getLong(NotificationActionReceiver.EXTRA_MESSAGE_ID, -1)
        val authSession = inputData.getString(NotificationActionReceiver.EXTRA_AUTH_SESSION)
        val action = MessageWorkerActions.acknowledgeMessage ?: return Result.failure()
        if (channelId <= 0 || messageId <= 0 ||
            !NotificationActionSession.matches(authSession, NotificationClient.getSettings().token)
        ) return Result.failure()
        return runCatching { if (action(channelId, messageId)) Result.success() else retryOrFail() }
            .getOrElse { retryOrFail() }
    }

    private fun retryOrFail() = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
}

private const val MAX_ATTEMPTS = 5
