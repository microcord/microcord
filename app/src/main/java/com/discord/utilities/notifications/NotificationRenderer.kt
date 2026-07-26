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
import com.discord.utilities.fcm.NotificationClient

class NotificationRenderer(private val cache: NotificationCache = NotificationCache()) {
    fun renderLocal(context: Context, data: NotificationData, settings: NotificationClient.SettingsV2 = NotificationClient.getSettings()) =
        render(context, data.copy(source = NotificationSource.LOCAL), settings)

    fun renderGateway(context: Context, data: NotificationData, settings: NotificationClient.SettingsV2 = NotificationClient.getSettings()) =
        render(context, data.copy(source = NotificationSource.GATEWAY), settings)

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun render(
        context: Context,
        data: NotificationData,
        settings: NotificationClient.SettingsV2 = NotificationClient.getSettings(),
    ): Boolean {
        if (!canNotify(context, settings.isEnabled) || !isValid(data)) return false
        initNotificationChannels(context)
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
            .setDefaults(notificationDefaults(settings))
            .setCategory(if (data.kind == NotificationKind.SOCIAL) NotificationCompat.CATEGORY_SOCIAL else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (private) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup("manticord.channel.${data.channelId}")
            .setNumber(snapshot.messages.size)
            .setContentIntent(contentIntent(context, data))
            .setDeleteIntent(actionIntent(context, data, NotificationActionReceiver.ACTION_DELETE))

        // Notification channels carry sound/vibration/light policy from API 26; below that the
        // notification itself has to, which is why the reference only sets these on the builder.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !settings.isDisableBlink) {
            builder.setLights(ContextCompat.getColor(context, R.color.brand_500), LIGHT_PERIOD_MS, LIGHT_PERIOD_MS)
        }

        if (private) builder.setPublicVersion(
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notifications_white_24dp)
                .setContentTitle(context.getString(R.string.manticord_app_name))
                .setContentText(context.getString(R.string.new_messages))
                .build(),
        )
        if (data.kind == NotificationKind.MESSAGE || data.kind == NotificationKind.DIRECT_MESSAGE) {
            builder.setStyle(messageStyle(snapshot.messages))
            data.messageId?.let {
                builder.addAction(
                    R.drawable.ic_check_white_24dp,
                    context.getString(R.string.mark_as_read),
                    actionIntent(context, data, NotificationActionReceiver.ACTION_MARK_READ),
                )
            }
            // A reply action the user cannot act on is worse than none: the reference suppresses it
            // for every channel where SEND_MESSAGES is denied.
            if (data.allowReply && settings.canReplyTo(data.channelId)) builder.addAction(replyAction(context, data))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(data.body))
        }
        NotificationManagerCompat.from(context).notify(snapshot.notificationId, builder.build())
        return true
    }

    /**
     * Cancels every notification shown for [channelId].
     *
     * [isAckRequest] marks a clear driven by our own read-ack; see [NotificationCache.removeChannel].
     */
    @JvmOverloads
    fun clearChannel(context: Context, channelId: Long, isAckRequest: Boolean = false): Boolean {
        if (!cache.consumeAckExemption(channelId, isAckRequest)) return false
        cache.removeChannel(channelId)
        // The id is derived from the channel id rather than read back from the cache, so a receiver
        // woken into a fresh process — where the in-memory cache is empty — still cancels the
        // notification that is actually on screen.
        NotificationManagerCompat.from(context).cancel(NotificationCache.notificationId(channelId))
        return true
    }

    fun setIgnoreNextClearForAck(channelId: Long, shouldIgnore: Boolean) =
        cache.setIgnoreNextClearForAck(channelId, shouldIgnore)

    fun cancel(context: Context, notificationId: Int) {
        if (notificationId > 0) NotificationManagerCompat.from(context).cancel(notificationId)
    }

    fun canNotify(context: Context, enabled: Boolean = true): Boolean = enabled &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED)

    private fun notificationDefaults(settings: NotificationClient.SettingsV2): Int {
        var defaults = Notification.DEFAULT_LIGHTS
        if (!settings.isDisableSound) defaults = defaults or Notification.DEFAULT_SOUND
        if (!settings.isDisableVibrate) defaults = defaults or Notification.DEFAULT_VIBRATE
        return defaults
    }

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
        val input = RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_REPLY)
            .setLabel(context.getString(R.string.notification_reply))
            .build()
        val intent = Intent(
            NotificationActionReceiver.ACTION_REPLY,
            actionUri(NotificationActionReceiver.ACTION_REPLY, data.channelId),
            context,
            NotificationActionReceiver::class.java,
        ).putExtra(NotificationActionReceiver.EXTRA_CHANNEL_ID, data.channelId)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, NotificationCache.notificationId(data.channelId))
            .putExtra(NotificationActionReceiver.EXTRA_AUTH_GENERATION, data.authGeneration)
        val pending = PendingIntent.getBroadcast(context, requestCode(data, 2), intent, mutableReplyFlags())
        return NotificationCompat.Action.Builder(R.drawable.ic_notifications_white_24dp, context.getString(R.string.notification_reply), pending)
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

    /**
     * The only mutable PendingIntent in the app.
     *
     * `RemoteInput` works by having the system write the typed text into the intent it fires, which
     * is impossible with FLAG_IMMUTABLE. The blast radius is bounded because the intent names its
     * component explicitly (so only [NotificationActionReceiver], which is not exported, can
     * receive it), it carries no data URI another app could redirect, and every field it does carry
     * — channel id, notification id, auth generation — is re-validated by
     * [NotificationActionValidator] and re-checked against the live auth generation before any work
     * is enqueued.
     */
    private fun mutableReplyFlags() = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    fun initNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationKind.entries.forEach { kind ->
            manager.createNotificationChannel(NotificationChannel(kind.channelId, kind.channelId, NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = if (kind == NotificationKind.GENERAL) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE
            })
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = NotificationClient.NOTIF_CHANNEL_MESSAGES
        const val CHANNEL_DIRECT_MESSAGES = NotificationClient.NOTIF_CHANNEL_MESSAGES_DIRECT
        const val CHANNEL_SOCIAL = NotificationClient.NOTIF_CHANNEL_SOCIAL
        const val CHANNEL_GENERAL = NotificationClient.NOTIF_GENERAL

        private const val LIGHT_PERIOD_MS = 1_500

        private val NotificationKind.channelId: String get() = when (this) {
            NotificationKind.MESSAGE -> CHANNEL_MESSAGES
            NotificationKind.DIRECT_MESSAGE -> CHANNEL_DIRECT_MESSAGES
            NotificationKind.SOCIAL -> CHANNEL_SOCIAL
            NotificationKind.GENERAL -> CHANNEL_GENERAL
        }
    }
}
