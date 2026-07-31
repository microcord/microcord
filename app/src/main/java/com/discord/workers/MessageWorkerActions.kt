package com.discord.workers

import android.content.Context
import androidx.work.ListenableWorker

object MessageWorkerActions {
    @Volatile var sendPendingMessages: (() -> Boolean)? = null
    @Volatile var acknowledgeMessage: ((channelId: Long, messageId: Long) -> Boolean)? = null
    @Volatile var sendDirectReply: ((channelId: Long, channelName: String, content: String, nonce: String?, stickerId: Long) -> Boolean)? = null
    @Volatile var declineCall: ((channelId: Long, messageId: Long) -> Boolean)? = null
    @Volatile var timedMute: ((guildId: Long, channelId: Long, untilTimestampMs: Long) -> Boolean)? = null
    @Volatile var isAuthenticated: (() -> Boolean)? = null
    @Volatile var isConnected: ((Context) -> Boolean)? = null
    @Volatile var isCurrentAuthGeneration: ((generation: Int) -> Boolean)? = null
}

internal enum class WorkerOutcome {
    SUCCESS, RETRY, FAILURE;

    fun toResult(): ListenableWorker.Result = when (this) {
        SUCCESS -> ListenableWorker.Result.success()
        RETRY -> ListenableWorker.Result.retry()
        FAILURE -> ListenableWorker.Result.failure()
    }
}
