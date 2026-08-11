package com.discord.utilities.fcm

import android.app.ActivityManager
import android.content.Context
import com.discord.api.channel.Channel
import com.discord.app.AppLog
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.icon.IconUtils
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
        if ((getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses.orEmpty().any {
            it.processName == packageName && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }) {
            AppLog.d("Dropping FCM because the main app is foreground")
            return
        }
        AppLog.d(
            "FCM message received. type=${message.data["type"]} fields=${message.data.size} " +
                "notification=${message.notification != null}",
        )
        val payload = runCatching { NotificationData.parsePushPayload(message.data) }
            .onFailure { AppLog.e("Unparseable push payload: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        if (!payload.isValid()) {
            AppLog.w("Dropping invalid FCM payload. type=${payload.type}")
            return
        }
        val delivery = NotificationClient.handlePushNotification(payload.toNotificationData(), applicationContext)
        AppLog.d("FCM delivery result=$delivery type=${payload.type}")
    }

    private fun NotificationData.PushPayload.toNotificationData(): NotificationData {
        val isDirect = normalizedGuildId == 0L
        val messageNotification = pushType == PushType.MESSAGE_CREATE
        val resolvedTitle = if (messageNotification) messageTitle() else customTitle ?: channel.name ?: guildName.orEmpty()
        val username = userUsername.orEmpty()
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
            title = resolvedTitle,
            body = NotificationData.messageBody(
                this@FCMMessagingService,
                messageContent,
                sticker?.name,
                attachmentFilename,
            ),
            sender = subtitle?.takeIf(String::isNotBlank)?.let { "$username $it" } ?: username.ifBlank { resolvedTitle },
            conversationTitle = resolvedTitle.takeUnless { channel.type == Channel.DM },
            isGroupConversation = isGroupConversation,
            senderKey = "$userId:${subtitle.orEmpty()}",
            senderImportant = relationshipType == 1,
            iconUrl = mainIconUrl(),
            avatarIconUrl = IconUtils.getForGuildMember(
                guildMemberAvatar,
                guildId,
                userId,
                size = 128,
                allowAnimation = false,
            ) ?: IconUtils.getForUser(userId, userAvatar, userDiscriminator, size = 128),
            timestamp = messageId.takeIf { it > 0L }?.let(SnowflakeUtils::toTimestamp) ?: System.currentTimeMillis(),
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

internal fun NotificationData.PushPayload.messageTitle(): String = when (channel.type) {
    Channel.DM -> userUsername.orEmpty()
    Channel.GROUP_DM -> channel.name ?: userUsername.orEmpty()
    Channel.GUILD_TEXT,
    Channel.GUILD_VOICE,
    Channel.GUILD_ANNOUNCEMENT,
    Channel.ANNOUNCEMENT_THREAD,
    Channel.PUBLIC_THREAD,
    Channel.PRIVATE_THREAD,
    -> listOfNotNull(guildName?.takeIf(String::isNotBlank), channel.name?.takeIf(String::isNotBlank))
        .joinToString(" #")
    else -> customTitle ?: channel.name ?: guildName.orEmpty()
}

internal fun NotificationData.PushPayload.mainIconUrl(): String? = when (channel.type) {
    Channel.DM -> IconUtils.getForUser(userId, userAvatar, userDiscriminator, size = 128)
    Channel.GROUP_DM -> IconUtils.getForChannel(channel.id, channel.icon, channel.type, false, 128)
    Channel.GUILD_TEXT,
    Channel.GUILD_VOICE,
    Channel.GUILD_ANNOUNCEMENT,
    Channel.ANNOUNCEMENT_THREAD,
    Channel.PUBLIC_THREAD,
    Channel.PRIVATE_THREAD,
    -> IconUtils.getForGuild(guildId, guildIcon, allowAnimation = false, size = 128)
    else -> IconUtils.getForChannel(channel.id, channel.icon, channel.type, false, 128)
}
