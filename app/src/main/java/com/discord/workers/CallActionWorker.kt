package com.discord.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class CallActionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork() = execute(
        authenticated = MessageWorkerActions.isAuthenticated?.invoke() == true,
        connected = MessageWorkerActions.isConnected?.invoke(applicationContext) == true,
        channelId = inputData.getLong(KEY_CHANNEL_ID, -1L),
        messageId = inputData.getLong(KEY_MESSAGE_ID, -1L),
        runAttemptCount = runAttemptCount,
        action = MessageWorkerActions.declineCall,
    ).toResult()

    companion object {
        const val KEY_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val KEY_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        const val MAX_ATTEMPTS = 3

        internal fun execute(
            authenticated: Boolean,
            connected: Boolean,
            channelId: Long,
            messageId: Long,
            runAttemptCount: Int,
            action: ((Long, Long) -> Boolean)?,
        ): WorkerOutcome {
            if (!authenticated) return WorkerOutcome.FAILURE
            if (!connected) return WorkerOutcome.RETRY
            if (action == null) return WorkerOutcome.FAILURE
            return try {
                if (action(channelId, messageId)) WorkerOutcome.SUCCESS
                else if (runAttemptCount < MAX_ATTEMPTS) WorkerOutcome.RETRY else WorkerOutcome.FAILURE
            } catch (_: Throwable) {
                if (runAttemptCount < MAX_ATTEMPTS) WorkerOutcome.RETRY else WorkerOutcome.FAILURE
            }
        }
    }
}
