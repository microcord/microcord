package com.discord.utilities.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.app.AppActivity

class NotificationRenderer(private val cache: NotificationCache = NotificationCache()) {
    fun renderLocal(context: Context, data: NotificationData, enabled: Boolean = true) =
        render(context, data.copy(source = NotificationSource.LOCAL), enabled)

    fun renderGateway(context: Context, data: NotificationData, enabled: Boolean = true) =
        render(context, data.copy(source = NotificationSource.GATEWAY), enabled)

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun render(context: Context, data: NotificationData, enabled: Boolean = true): Boolean {
        if (!canNotify(context, enabled) || !isValid(data)) return false
        createChannels(context)
        val snapshot = cache.add(data)
        val channelId = data.kind.channelId
        val private = data.kind != NotificationKind.GENERAL
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notifications_white_24dp)
            .setContentTitle(data.title)
            .setContentText(data.body)
            .setWhen(data.timestamp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(snapshot.messages.size > 1)
            .setCategory(if (data.kind == NotificationKind.SOCIAL) NotificationCompat.CATEGORY_SOCIAL else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (private) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup("manticord.channel.${data.channelId}")
            .setNumber(snapshot.messages.size)
            .setContentIntent(contentIntent(context, data))
            .setDeleteIntent(actionIntent(context, data, NotificationActionReceiver.ACTION_DELETE))

        if (private) builder.setPublicVersion(
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notifications_white_24dp)
                .setContentTitle("Manticord")
                .setContentText("New notification")
                .build(),
        )
        if (data.kind == NotificationKind.MESSAGE || data.kind == NotificationKind.DIRECT_MESSAGE) {
            builder.setStyle(messageStyle(snapshot.messages))
            data.messageId?.let {
                builder.addAction(
                    R.drawable.ic_check_white_24dp,
                    "Mark As Read",
                    actionIntent(context, data, NotificationActionReceiver.ACTION_MARK_READ),
                )
            }
            if (data.allowReply) builder.addAction(replyAction(context, data))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(data.body))
        }
        NotificationManagerCompat.from(context).notify(snapshot.notificationId, builder.build())
        return true
    }

    fun cancelChannel(context: Context, channelId: Long): Boolean {
        val id = cache.removeChannel(channelId) ?: return false
        NotificationManagerCompat.from(context).cancel(id)
        return true
    }

    fun cancel(context: Context, notificationId: Int) {
        if (notificationId > 0) NotificationManagerCompat.from(context).cancel(notificationId)
    }

    fun canNotify(context: Context, enabled: Boolean = true): Boolean = enabled &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED)

    private fun isValid(data: NotificationData) = data.channelId > 0 && data.authGeneration >= 0 &&
        data.title.isNotBlank() && data.body.isNotBlank() &&
        (data.messageId == null || data.messageId > 0)

    private fun messageStyle(messages: List<NotificationData>): NotificationCompat.MessagingStyle {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").setKey("me").build())
            .setConversationTitle(messages.last().title)
            .setGroupConversation(messages.last().kind == NotificationKind.MESSAGE)
        messages.forEach { message ->
            style.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    message.body,
                    message.timestamp,
                    Person.Builder().setName(message.sender).setKey(message.sender).build(),
                ),
            )
        }
        return style
    }

    private fun contentIntent(context: Context, data: NotificationData): PendingIntent {
        val guild = data.guildId?.toString() ?: "@me"
        val message = data.messageId?.let { "/$it" }.orEmpty()
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("manticord://app/channels/$guild/${data.channelId}$message"),
            context,
            AppActivity.Main::class.java,
        )
        return PendingIntent.getActivity(context, requestCode(data, 0), intent, immutableFlags())
    }

    private fun actionIntent(context: Context, data: NotificationData, action: String): PendingIntent {
        val intent = Intent(action, actionUri(action, data.channelId), context, NotificationActionReceiver::class.java)
            .putExtra(NotificationActionReceiver.EXTRA_CHANNEL_ID, data.channelId)
            .putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, data.messageId ?: -1L)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, NotificationCache.notificationId(data.channelId))
            .putExtra(NotificationActionReceiver.EXTRA_AUTH_GENERATION, data.authGeneration)
        return PendingIntent.getBroadcast(context, requestCode(data, action.hashCode()), intent, immutableFlags())
    }

    private fun replyAction(context: Context, data: NotificationData): NotificationCompat.Action {
        val input = RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_REPLY).setLabel("Reply").build()
        val intent = Intent(
            NotificationActionReceiver.ACTION_REPLY,
            actionUri(NotificationActionReceiver.ACTION_REPLY, data.channelId),
            context,
            NotificationActionReceiver::class.java,
        ).putExtra(NotificationActionReceiver.EXTRA_CHANNEL_ID, data.channelId)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, NotificationCache.notificationId(data.channelId))
            .putExtra(NotificationActionReceiver.EXTRA_AUTH_GENERATION, data.authGeneration)
        val pending = PendingIntent.getBroadcast(context, requestCode(data, 2), intent, mutableReplyFlags())
        return NotificationCompat.Action.Builder(R.drawable.ic_notifications_white_24dp, "Reply", pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun requestCode(data: NotificationData, salt: Int) =
        NotificationCache.notificationId(data.channelId) * 31 + salt

    private fun actionUri(action: String, channelId: Long) =
        Uri.parse("manticord://notification/${action.substringAfterLast('.')}/$channelId")

    private fun immutableFlags() = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun mutableReplyFlags() = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationKind.entries.forEach { kind ->
            manager.createNotificationChannel(NotificationChannel(kind.channelId, kind.channelId, NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = if (kind == NotificationKind.GENERAL) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE
            })
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "Messages"
        const val CHANNEL_DIRECT_MESSAGES = "DM"
        const val CHANNEL_SOCIAL = "Social"
        const val CHANNEL_GENERAL = "General"

        private val NotificationKind.channelId: String get() = when (this) {
            NotificationKind.MESSAGE -> CHANNEL_MESSAGES
            NotificationKind.DIRECT_MESSAGE -> CHANNEL_DIRECT_MESSAGES
            NotificationKind.SOCIAL -> CHANNEL_SOCIAL
            NotificationKind.GENERAL -> CHANNEL_GENERAL
        }
    }
}
