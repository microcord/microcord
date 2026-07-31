package com.discord.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class MessageSendWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork() = execute(
        authenticated = MessageWorkerActions.isAuthenticated?.invoke() == true,
        connected = MessageWorkerActions.isConnected?.invoke(applicationContext) == true,
        currentAuthGeneration = MessageWorkerActions.isCurrentAuthGeneration
            ?.invoke(inputData.getInt(KEY_AUTH_GENERATION, -1)) == true,
        channelId = inputData.getLong(KEY_CHANNEL_ID, -1L),
        channelName = inputData.getString(KEY_CHANNEL_NAME).orEmpty(),
        content = inputData.getString(KEY_MESSAGE_CONTENT),
        nonce = inputData.getString(KEY_MESSAGE_ID),
        stickerId = inputData.getLong(KEY_STICKER_ID, -1L),
        runAttemptCount = runAttemptCount,
        action = MessageWorkerActions.sendDirectReply,
    ).toResult()

    companion object {
        const val KEY_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val KEY_CHANNEL_NAME = "com.discord.intent.extra.EXTRA_CHANNEL_NAME"
        const val KEY_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        const val KEY_MESSAGE_CONTENT = "MESSAGE_CONTENT"
        const val KEY_STICKER_ID = "com.discord.intent.extra.EXTRA_STICKER_ID"
        const val KEY_AUTH_GENERATION = "notification_auth_generation"
        const val MAX_ATTEMPTS = 3

        internal fun execute(
            authenticated: Boolean,
            connected: Boolean,
            currentAuthGeneration: Boolean,
            channelId: Long,
            channelName: String,
            content: String?,
            nonce: String?,
            stickerId: Long,
            runAttemptCount: Int,
            action: ((Long, String, String, String?, Long) -> Boolean)?,
        ): WorkerOutcome {
            if (!authenticated || !currentAuthGeneration) return WorkerOutcome.FAILURE
            if (!connected) {
                return if (runAttemptCount < MAX_ATTEMPTS) WorkerOutcome.RETRY else WorkerOutcome.FAILURE
            }
            if (content == null) return WorkerOutcome.SUCCESS
            if (action == null) return WorkerOutcome.FAILURE
            return try {
                if (action(channelId, channelName, content, nonce, stickerId)) {
                    WorkerOutcome.SUCCESS
                } else {
                    WorkerOutcome.FAILURE
                }
            } catch (_: Throwable) {
                WorkerOutcome.FAILURE
            }
        }
    }
}
