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
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.discord.R
import com.discord.api.message.Message
import com.discord.app.AppActivity
import com.discord.utilities.ShareUtils
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImagesBitmap
import rx.Observable

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
        val channelId = PushChannel.fromWireValue(data.notificationChannel)?.id ?: data.kind.channelId
        val private = data.kind != NotificationKind.GENERAL
        val authSession = NotificationActionSession.fromToken(settings.token)
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
            .setGroup(if (data.channelId > 0L) "manticord.channel.${data.channelId}" else "manticord.general")
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
                // `new_messages` is the unread divider's template ("{count} since {timestamp}"),
                // not a standalone label.
                .setContentText(context.getString(R.string.new_messages_divider))
                .build(),
        )
        if (data.kind == NotificationKind.MESSAGE || data.kind == NotificationKind.DIRECT_MESSAGE) {
            builder.setStyle(messageStyle(context, snapshot.messages, emptyMap()))
            if (data.messageId != null && authSession != null) {
                builder.addAction(
                    R.drawable.ic_check_white_24dp,
                    context.getString(R.string.mark_as_read),
                    actionIntent(
                        context,
                        data,
                        NotificationActionReceiver.ACTION_MARK_READ,
                        authSession,
                    ),
                )
            }
            // A reply action the user cannot act on is worse than none: the reference suppresses it
            // for every channel where SEND_MESSAGES is denied.
            if (data.allowReply && settings.canReplyTo(data.channelId)) {
                authSession?.let { builder.addAction(replyAction(context, data, it)) }
            }
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(data.body))
        }
        displayNotification(context, snapshot.notificationId, builder)
        loadNotificationBitmaps(snapshot.messages, data).subscribe({ bitmaps ->
            val adaptiveBitmaps = bitmaps.mapValues { ShareUtils.toAdaptiveIconBitmap(context, it.value) }
            if (data.kind == NotificationKind.MESSAGE || data.kind == NotificationKind.DIRECT_MESSAGE) {
                builder.setStyle(messageStyle(context, snapshot.messages, adaptiveBitmaps))
                data.iconUrl?.let(adaptiveBitmaps::get)?.let { shortcutIcon ->
                    ShareUtils.pushConversationShortcut(
                        context,
                        ShareUtils.ConversationShortcut(
                            channelId = data.channelId,
                            label = data.title,
                            persons = snapshot.messages.distinctBy(NotificationData::senderKey).map {
                                ShareUtils.ShortcutPerson(it.senderKey.substringBefore(':').toLongOrNull() ?: 0L, it.sender)
                            },
                            icon = shortcutIcon,
                        ),
                    )
                    builder.setShortcutId(data.channelId.toString())
                }
            }
            displayNotification(context, snapshot.notificationId, builder)
        }, { })
        data.iconUrl?.let { iconUrl ->
            MGImagesBitmap.getBitmap(iconUrl, imageIsCircle = true).subscribe({ bitmap ->
                builder.setLargeIcon(bitmap)
                displayNotification(context, snapshot.notificationId, builder)
            }, { })
        }
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

    fun displayReply(
        context: Context,
        channelId: Long,
        message: Message,
        settings: NotificationClient.SettingsV2,
    ): Boolean {
        val previous = cache.get(channelId)?.messages?.lastOrNull() ?: return false
        val author = message.author
        val avatar = (author?.avatar as? com.discord.nullserializable.NullSerializable.Value<String>)?.value
        return render(
            context,
            previous.copy(
                messageId = message.id,
                body = NotificationData.messageBody(
                    context,
                    message.content,
                    message.stickers?.firstOrNull()?.name,
                    message.attachments?.firstOrNull()?.filename,
                ),
                sender = context.getString(R.string.me),
                senderKey = "me",
                senderImportant = true,
                avatarIconUrl = IconUtils.getForUser(
                    author?.id,
                    avatar,
                    author?.discriminator?.toIntOrNull(),
                    size = 128,
                ),
                timestamp = message.timestamp?.dateTimeMillis ?: previous.timestamp + 1L,
                source = NotificationSource.LOCAL,
            ),
            settings,
        )
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun displaySent(context: Context, channelId: Long, channelName: String, success: Boolean) {
        if (!canNotify(context)) return
        initNotificationChannels(context)
        val text = context.getString(
            if (success) R.string.notification_reply_success else R.string.notification_reply_failed,
        ).replace("{recipient}", channelName)
        val data = NotificationData(
            kind = NotificationKind.MESSAGE,
            channelId = channelId,
            title = channelName,
            body = text,
            authGeneration = 0,
        )
        NotificationManagerCompat.from(context).notify(
            NotificationCache.notificationId(channelId),
            NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_notifications_white_24dp)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentText(text)
                .setContentIntent(contentIntent(context, data))
                .setTimeoutAfter(REPLY_STATUS_TIMEOUT_MS)
                .build(),
        )
    }

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

    private fun messageStyle(
        context: Context,
        messages: List<NotificationData>,
        bitmaps: Map<String, Bitmap>,
    ): NotificationCompat.MessagingStyle {
        val latest = messages.last()
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName(context.getString(R.string.me)).setKey("me").build(),
        )
            .setConversationTitle(latest.conversationTitle)
            .setGroupConversation(latest.isGroupConversation)
        messages.forEach { message ->
            val icon = message.avatarIconUrl?.let(bitmaps::get)?.let(IconCompat::createWithAdaptiveBitmap)
            val sender = Person.Builder()
                .setName(message.sender)
                .setKey(message.senderKey)
                .setImportant(message.senderImportant)
                .setIcon(icon)
                .build()
            style.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    message.body,
                    message.timestamp + if (icon == null) 0L else 1L,
                    sender,
                ),
            )
        }
        return style
    }

    private fun loadNotificationBitmaps(
        messages: List<NotificationData>,
        latest: NotificationData,
    ): Observable<Map<String, Bitmap>> {
        val urls = (messages.mapNotNull(NotificationData::avatarIconUrl) + listOfNotNull(latest.iconUrl))
            .filter(String::isNotBlank)
            .toSet()
        return Observable.from(urls)
            .flatMap { url ->
                MGImagesBitmap.getBitmap(url, imageIsCircle = false)
                    .map { bitmap -> url to bitmap }
                    .onErrorResumeNext(Observable.empty())
            }
            .toMap({ it.first }, { it.second })
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun displayNotification(
        context: Context,
        notificationId: Int,
        builder: NotificationCompat.Builder,
    ) {
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, builder.build()) }
    }

    private fun contentIntent(context: Context, data: NotificationData): PendingIntent {
        // The server's own deep link wins: a friend request or quest has no channel to open, and
        // reconstructing a `channels/...` url for one lands on nothing.
        val uri = data.deepLink?.takeIf(String::isNotBlank) ?: run {
            val guild = data.guildId?.toString() ?: "@me"
            val message = data.messageId?.let { "/$it" }.orEmpty()
            "manticord://app/channels/$guild/${data.channelId}$message"
        }
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(uri),
            context,
            AppActivity.Main::class.java,
        )
        return PendingIntent.getActivity(context, requestCode(data, 0), intent, immutableFlags())
    }

    private fun actionIntent(
        context: Context,
        data: NotificationData,
        action: String,
        authSession: String? = null,
    ): PendingIntent {
        val intent = Intent(action, actionUri(action, data.channelId), context, NotificationActionReceiver::class.java)
            .putExtra(NotificationActionReceiver.EXTRA_CHANNEL_ID, data.channelId)
            .putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, data.messageId ?: -1L)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, NotificationCache.notificationId(data.channelId))
        authSession?.let { intent.putExtra(NotificationActionReceiver.EXTRA_AUTH_SESSION, it) }
        return PendingIntent.getBroadcast(context, requestCode(data, action.hashCode()), intent, immutableFlags())
    }

    private fun replyAction(context: Context, data: NotificationData, authSession: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_REPLY)
            .setLabel(context.getString(R.string.notification_reply))
            .build()
        val intent = Intent(
            NotificationActionReceiver.ACTION_REPLY,
            actionUri(NotificationActionReceiver.ACTION_REPLY, data.channelId),
            context,
            NotificationActionReceiver::class.java,
        ).putExtra(NotificationActionReceiver.EXTRA_CHANNEL_ID, data.channelId)
            .putExtra(NotificationActionReceiver.EXTRA_CHANNEL_NAME, data.title)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, NotificationCache.notificationId(data.channelId))
            .putExtra(NotificationActionReceiver.EXTRA_AUTH_SESSION, authSession)
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
     * receive it), its data URI contains no credentials, and every field it carries — channel id,
     * notification id, auth-session fingerprint — is re-validated by [NotificationActionValidator]
     * and checked against the current account before any work is enqueued.
     */
    private fun mutableReplyFlags() = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    fun initNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Ids overlap between the two: a [PushChannel] label is the readable name for a channel the
        // kinds already needed, so each id is registered once.
        val labels = LinkedHashMap<String, String>()
        NotificationKind.entries.forEach { labels[it.channelId] = it.channelId }
        PushChannel.entries.forEach { labels[it.id] = it.label }
        labels.forEach { (id, label) ->
            manager.createNotificationChannel(
                NotificationChannel(id, label, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    lockscreenVisibility = if (id == NotificationKind.GENERAL.channelId) {
                        Notification.VISIBILITY_PUBLIC
                    } else {
                        Notification.VISIBILITY_PRIVATE
                    }
                },
            )
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = NotificationClient.NOTIF_CHANNEL_MESSAGES
        const val CHANNEL_DIRECT_MESSAGES = NotificationClient.NOTIF_CHANNEL_MESSAGES_DIRECT
        const val CHANNEL_SOCIAL = NotificationClient.NOTIF_CHANNEL_SOCIAL
        const val CHANNEL_GENERAL = NotificationClient.NOTIF_GENERAL

        private const val LIGHT_PERIOD_MS = 1_500
        private const val REPLY_STATUS_TIMEOUT_MS = 3_000L

        private val NotificationKind.channelId: String get() = when (this) {
            NotificationKind.MESSAGE -> CHANNEL_MESSAGES
            NotificationKind.DIRECT_MESSAGE -> CHANNEL_DIRECT_MESSAGES
            NotificationKind.SOCIAL -> CHANNEL_SOCIAL
            NotificationKind.GENERAL -> CHANNEL_GENERAL
        }
    }
}
