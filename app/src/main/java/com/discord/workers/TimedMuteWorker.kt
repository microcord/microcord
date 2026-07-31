package com.discord.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory

class TimedMuteWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    var clock: Clock = ClockFactory.get()

    override fun doWork() = execute(
        authenticated = MessageWorkerActions.isAuthenticated?.invoke() == true,
        connected = MessageWorkerActions.isConnected?.invoke(applicationContext) == true,
        guildId = inputData.getLong(KEY_GUILD_ID, -1L),
        channelId = inputData.getLong(KEY_CHANNEL_ID, -1L),
        untilTimestampMs = inputData.getLong(KEY_UNTIL_TIMESTAMP_MS, 0L),
        nowTimestampMs = clock.currentTimeMillis(),
        runAttemptCount = runAttemptCount,
        action = MessageWorkerActions.timedMute,
    ).toResult()

    companion object {
        const val KEY_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        const val KEY_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val KEY_UNTIL_TIMESTAMP_MS = "com.discord.intent.extra.EXTRA_UNTIL_TIMESTAMP_MS"
        const val MAX_ATTEMPTS = 3

        internal fun execute(
            authenticated: Boolean,
            connected: Boolean,
            guildId: Long,
            channelId: Long,
            untilTimestampMs: Long,
            nowTimestampMs: Long,
            runAttemptCount: Int,
            action: ((Long, Long, Long) -> Boolean)?,
        ): WorkerOutcome {
            if (!authenticated) return WorkerOutcome.FAILURE
            if (!connected) return WorkerOutcome.RETRY
            if (guildId == -1L || channelId == -1L) return WorkerOutcome.FAILURE
            if (nowTimestampMs > untilTimestampMs) return WorkerOutcome.SUCCESS
            if (action == null) return WorkerOutcome.FAILURE
            return try {
                if (action(guildId, channelId, untilTimestampMs)) WorkerOutcome.SUCCESS
                else if (runAttemptCount < MAX_ATTEMPTS) WorkerOutcome.RETRY else WorkerOutcome.FAILURE
            } catch (_: Throwable) {
                if (runAttemptCount < MAX_ATTEMPTS) WorkerOutcome.RETRY else WorkerOutcome.FAILURE
            }
        }
    }
}
