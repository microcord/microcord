package com.discord.utilities.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.discord.utilities.fcm.NotificationClient
import com.discord.workers.MessageSendWorker
import java.util.concurrent.TimeUnit

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1)
        val command = NotificationActionValidator.validate(
            intent.action,
            channelId,
            intent.getLongExtra(EXTRA_MESSAGE_ID, -1),
            intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1),
            intent.getStringExtra(EXTRA_AUTH_SESSION),
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_REPLY),
        ) ?: run {
            if (intent.action == ACTION_REPLY && channelId > 0) NotificationClient.clear(channelId, context)
            return
        }
        when (command) {
            is NotificationCommand.MarkRead -> {
                if (!NotificationActionSession.matches(command.authSession, NotificationClient.getSettings().token)) return
                if (enqueueMarkRead(context, command)) {
                    NotificationClient.clear(command.channelId, context)
                }
            }
            is NotificationCommand.Reply -> {
                if (!NotificationActionSession.matches(command.authSession, NotificationClient.getSettings().token)) return
                // The reply's own read-ack must not cancel a notification that lands while the
                // send is in flight, so this channel skips exactly one ack-driven clear.
                NotificationClient.setIgnoreNextClearForAck(command.channelId, true)
                if (!enqueueDirectReply(
                    context,
                    command.channelId,
                    intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty(),
                    command.text,
                    requireNotNull(command.authSession),
                )) NotificationClient.setIgnoreNextClearForAck(command.channelId, false)
            }
            is NotificationCommand.Delete -> {
                NotificationClient.clear(command.channelId, context)
            }
            is NotificationCommand.Cancel -> NotificationManagerCompat.from(context).cancel(command.notificationId)
        }
    }

    private fun enqueueDirectReply(
        context: Context,
        channelId: Long,
        channelName: String,
        reply: String,
        authSession: String,
    ): Boolean {
        val input = Data.Builder()
            .putLong(MessageSendWorker.KEY_CHANNEL_ID, channelId)
            .putString(MessageSendWorker.KEY_CHANNEL_NAME, channelName)
            .putString(MessageSendWorker.KEY_MESSAGE_ID, null)
            .putString(MessageSendWorker.KEY_MESSAGE_CONTENT, reply)
            .putLong(MessageSendWorker.KEY_STICKER_ID, -1L)
            .putString(MessageSendWorker.KEY_AUTH_SESSION, authSession)
            .build()
        return runCatching {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<MessageSendWorker>()
                    .setInputData(input)
                    .addTag("message")
                    .addTag("send")
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
            true
        }.getOrDefault(false)
    }

    private fun enqueueMarkRead(
        context: Context,
        command: NotificationCommand.MarkRead,
    ): Boolean {
        val input = Data.Builder()
            .putLong(EXTRA_CHANNEL_ID, command.channelId)
            .putLong(EXTRA_MESSAGE_ID, command.messageId)
            .putString(EXTRA_AUTH_SESSION, command.authSession)
            .build()
        return runCatching {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<NotificationMarkReadWorker>()
                    .setInputData(input)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                    .addTag("message")
                    .addTag("ack")
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
            true
        }.getOrDefault(false)
    }

    companion object {
        const val ACTION_MARK_READ = "dev.mantikafasi.manticord.notification.MARK_READ"
        const val ACTION_REPLY = "dev.mantikafasi.manticord.notification.REPLY"
        const val ACTION_DELETE = "dev.mantikafasi.manticord.notification.DELETE"
        const val ACTION_CANCEL = "dev.mantikafasi.manticord.notification.CANCEL"
        const val EXTRA_CHANNEL_ID = "notification_channel_id"
        const val EXTRA_MESSAGE_ID = "notification_message_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_AUTH_SESSION = "notification_auth_session"
        const val EXTRA_CHANNEL_NAME = "com.discord.intent.extra.EXTRA_CHANNEL_NAME"
        const val REMOTE_INPUT_REPLY = "notification_remote_reply"
    }
}
