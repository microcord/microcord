package com.discord.utilities.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.discord.utilities.fcm.NotificationClient
import com.discord.workers.MessageSendWorker
import com.discord.workers.MessageWorkerActions

data class NotificationActionCallbacks(
    val isCurrentAuthGeneration: (Int) -> Boolean,
    val markRead: (channelId: Long, messageId: Long) -> Boolean,
    val reply: (channelId: Long, text: String) -> Boolean,
    val delete: (channelId: Long) -> Unit,
    val cancel: (notificationId: Int) -> Unit,
)

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = NotificationActionValidator.validate(
            intent.action,
            intent.getLongExtra(EXTRA_CHANNEL_ID, -1),
            intent.getLongExtra(EXTRA_MESSAGE_ID, -1),
            intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1),
            intent.getIntExtra(EXTRA_AUTH_GENERATION, -1),
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_REPLY),
        ) ?: return
        val actions = callbacks
        val isCurrentGeneration = actions?.isCurrentAuthGeneration?.invoke(command.authGeneration)
            ?: MessageWorkerActions.isCurrentAuthGeneration?.invoke(command.authGeneration)
            ?: false
        if (!isCurrentGeneration) return
        when (command) {
            is NotificationCommand.MarkRead -> {
                if (actions == null) return
                enqueue<NotificationMarkReadWorker>(context, command.channelId, command.messageId, null, command.authGeneration)
                NotificationClient.clear(command.channelId, context, isAckRequest = true)
            }
            is NotificationCommand.Reply -> {
                // The reply's own read-ack must not cancel a notification that lands while the
                // send is in flight, so this channel skips exactly one ack-driven clear.
                NotificationClient.setIgnoreNextClearForAck(command.channelId, true)
                enqueueDirectReply(
                    context,
                    command.channelId,
                    intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty(),
                    command.text,
                    command.authGeneration,
                )
            }
            is NotificationCommand.Delete -> {
                if (actions == null) return
                NotificationClient.clear(command.channelId, context)
                actions.delete(command.channelId)
            }
            is NotificationCommand.Cancel -> actions?.cancel?.invoke(command.notificationId)
        }
    }

    private fun enqueueDirectReply(
        context: Context,
        channelId: Long,
        channelName: String,
        reply: String,
        generation: Int,
    ) {
        val input = Data.Builder()
            .putLong(MessageSendWorker.KEY_CHANNEL_ID, channelId)
            .putString(MessageSendWorker.KEY_CHANNEL_NAME, channelName)
            .putString(MessageSendWorker.KEY_MESSAGE_ID, null)
            .putString(MessageSendWorker.KEY_MESSAGE_CONTENT, reply)
            .putLong(MessageSendWorker.KEY_STICKER_ID, -1L)
            .putInt(MessageSendWorker.KEY_AUTH_GENERATION, generation)
            .build()
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<MessageSendWorker>()
                .setInputData(input)
                .addTag("message")
                .addTag("send")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    private inline fun <reified T : androidx.work.ListenableWorker> enqueue(
        context: Context,
        channelId: Long,
        messageId: Long,
        reply: String?,
        generation: Int,
    ) {
        val input = Data.Builder()
            .putLong(EXTRA_CHANNEL_ID, channelId)
            .putLong(EXTRA_MESSAGE_ID, messageId)
            .putString(EXTRA_REPLY, reply)
            .putInt(EXTRA_AUTH_GENERATION, generation)
            .build()
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<T>()
                .setInputData(input)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    companion object {
        const val ACTION_MARK_READ = "dev.mantikafasi.manticord.notification.MARK_READ"
        const val ACTION_REPLY = "dev.mantikafasi.manticord.notification.REPLY"
        const val ACTION_DELETE = "dev.mantikafasi.manticord.notification.DELETE"
        const val ACTION_CANCEL = "dev.mantikafasi.manticord.notification.CANCEL"
        const val EXTRA_CHANNEL_ID = "notification_channel_id"
        const val EXTRA_MESSAGE_ID = "notification_message_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_AUTH_GENERATION = "notification_auth_generation"
        const val EXTRA_CHANNEL_NAME = "com.discord.intent.extra.EXTRA_CHANNEL_NAME"
        const val EXTRA_REPLY = "notification_reply"
        const val REMOTE_INPUT_REPLY = "notification_remote_reply"

        @Volatile var callbacks: NotificationActionCallbacks? = null
    }
}
