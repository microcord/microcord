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
        val actions = callbacks ?: return
        if (!actions.isCurrentAuthGeneration(command.authGeneration)) return
        when (command) {
            is NotificationCommand.MarkRead -> enqueue<NotificationMarkReadWorker>(context, command.channelId, command.messageId, null, command.authGeneration)
            is NotificationCommand.Reply -> enqueue<NotificationReplyWorker>(context, command.channelId, -1, command.text, command.authGeneration)
            is NotificationCommand.Delete -> actions.delete(command.channelId)
            is NotificationCommand.Cancel -> actions.cancel(command.notificationId)
        }
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
        const val EXTRA_REPLY = "notification_reply"
        const val REMOTE_INPUT_REPLY = "notification_remote_reply"

        @Volatile var callbacks: NotificationActionCallbacks? = null
    }
}
