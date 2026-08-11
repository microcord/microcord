package com.discord.utilities.fcm

import com.discord.api.channel.Channel
import com.discord.app.AppLog
import com.discord.utilities.notifications.NotificationData
import com.discord.utilities.notifications.NotificationKind
import com.discord.utilities.notifications.NotificationSource
import com.discord.utilities.notifications.PushType
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives pushes from Firebase and hands them to [NotificationClient].
 *
 * The transport is the only thing here: parsing, gating and rendering already exist, so this
 * translates the flat string map Firebase delivers into the payload model and gets out of the way.
 */
class FCMMessagingService : FirebaseMessagingService() {

    /**
     * A new registration id. [NotificationClient] republishes it to `StoreNotifications`, which is
     * what posts it to `/users/@me/devices`.
     */
    override fun onNewToken(token: String) {
        NotificationClient.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = runCatching { NotificationData.parsePushPayload(message.data) }
            .onFailure { AppLog.e("Unparseable push payload: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        if (!payload.isValid()) return
        NotificationClient.handlePushNotification(payload.toNotificationData(), applicationContext)
    }

    private fun NotificationData.PushPayload.toNotificationData(): NotificationData {
        val isDirect = channel.guildId == 0L
        return NotificationData(
            kind = when (pushType) {
                PushType.MESSAGE_CREATE, PushType.FORUM_THREAD_CREATED ->
                    if (isDirect) NotificationKind.DIRECT_MESSAGE else NotificationKind.MESSAGE
                PushType.RELATIONSHIP_ADD -> NotificationKind.SOCIAL
                else -> NotificationKind.GENERAL
            },
            channelId = channel.id,
            messageId = messageId.takeIf { it != NotificationData.UNSET_LONG },
            guildId = normalizedGuildId.takeIf { it != 0L && it != NotificationData.UNSET_LONG },
            title = customTitle ?: channel.name ?: guildName.orEmpty(),
            body = subtitle ?: messageContent.orEmpty(),
            sender = userUsername ?: customTitle ?: channel.name.orEmpty(),
            // A push is only raised while the client is not holding the socket, so it lands in the
            // tray exactly as a gateway-sourced one does.
            source = NotificationSource.GATEWAY,
            // Push arrives with no session to compare against; the renderer only rejects negatives.
            authGeneration = 0,
            allowReply = pushType == PushType.MESSAGE_CREATE &&
                channel.type != Channel.GUILD_VOICE &&
                NotificationClient.getSettings().canReplyTo(channel.id),
            type = type,
        )
    }
}
