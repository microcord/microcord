package com.discord.utilities.notifications

import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.message.activity.MessageActivityType
import com.discord.api.sticker.Sticker
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.security.MessageDigest

enum class NotificationKind { MESSAGE, DIRECT_MESSAGE, SOCIAL, GENERAL }

enum class NotificationSource { LOCAL, GATEWAY }

enum class PushNotificationType(val wireValue: String) {
    REMINDER("reminder");

    companion object {
        fun fromWireValue(value: String?): PushNotificationType? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class PushType(val wireValue: String) {
    ACTIVITY_START("ACTIVITY_START"),
    APPLICATION_LIBRARY_INSTALL_COMPLETE("APPLICATION_LIBRARY_INSTALL_COMPLETE"),
    CALL_RING("CALL_RING"),
    FORUM_THREAD_CREATED("FORUM_THREAD_CREATED"),
    GENERIC_PUSH_NOTIFICATION_SENT("GENERIC_PUSH_NOTIFICATION_SENT"),
    GUILD_SCHEDULED_EVENT_UPDATE("GUILD_SCHEDULED_EVENT_UPDATE"),
    MESSAGE_CREATE("MESSAGE_CREATE"),
    RELATIONSHIP_ADD("RELATIONSHIP_ADD"),
    STAGE_INSTANCE_CREATE("STAGE_INSTANCE_CREATE");

    companion object {
        fun fromWireValue(value: String): PushType? = entries.firstOrNull { it.wireValue == value }
    }
}

data class NotificationData(
    val kind: NotificationKind,
    val channelId: Long,
    val messageId: Long? = null,
    val guildId: Long? = null,
    val title: String,
    val body: String,
    val sender: String = title,
    val timestamp: Long = System.currentTimeMillis(),
    val authGeneration: Int,
    val allowReply: Boolean = false,
    val source: NotificationSource = NotificationSource.LOCAL,
    /** Push payload discriminator (`MESSAGE_CREATE`, `top_messages_push`, …); null for local notifications. */
    val type: String? = null,
) {
    /** The complete 126.21 data-message payload. Values remain private user content and must not be logged. */
    data class PushPayload(
        val type: String,
        val messageId: Long,
        val messageActivityType: MessageActivityType?,
        val messageApplicationName: String?,
        val messageType: Int,
        val messageContent: String?,
        val parentChannel: Channel?,
        val channel: Channel,
        val acknowledgementChannelIds: List<Long>,
        val userId: Long,
        val userUsername: String?,
        val userDiscriminator: Int?,
        val userAvatar: String?,
        val guildMemberAvatar: String?,
        val relationshipType: Int,
        val deepLink: String?,
        val guildId: Long,
        val guildName: String?,
        val guildIcon: String?,
        val activityType: Int,
        val activityName: String?,
        val applicationId: Long,
        val applicationName: String?,
        val applicationIcon: String?,
        val sticker: Sticker?,
        val customTitle: String?,
        val subtitle: String?,
        val titleResourceName: String?,
        val subtitleResourceName: String?,
        val stageInstanceTopic: String?,
        val guildScheduledEventEntityType: GuildScheduledEventEntityType?,
        val guildScheduledEventId: Long?,
        val notificationType: PushNotificationType?,
        val trackingType: String?,
        val notificationChannel: String?,
        val expandSubtitle: Boolean,
        val notificationId: String?,
    ) {
        val pushType: PushType? get() = PushType.fromWireValue(type)

        val normalizedGuildId: Long get() = guildId

        val isGroupConversation: Boolean = pushType == PushType.MESSAGE_CREATE && channel.type in setOf(
            Channel.GUILD_TEXT,
            Channel.GUILD_VOICE,
            Channel.GROUP_DM,
            Channel.ANNOUNCEMENT_THREAD,
            Channel.PUBLIC_THREAD,
            Channel.PRIVATE_THREAD,
        )

        /** The reference gates forum pushes through the forum-redesign experiment at evaluation time. */
        fun isValid(canAccessForumChannels: (Long) -> Boolean = { true }): Boolean = when (pushType) {
            PushType.ACTIVITY_START -> activityType != ACTIVITY_TYPE_STREAMING
            PushType.FORUM_THREAD_CREATED -> canAccessForumChannels(normalizedGuildId)
            PushType.GUILD_SCHEDULED_EVENT_UPDATE -> guildScheduledEventEntityType in setOf(
                GuildScheduledEventEntityType.STAGE_INSTANCE,
                GuildScheduledEventEntityType.VOICE,
                GuildScheduledEventEntityType.EXTERNAL,
            )
            PushType.APPLICATION_LIBRARY_INSTALL_COMPLETE,
            PushType.CALL_RING,
            PushType.GENERIC_PUSH_NOTIFICATION_SENT,
            PushType.MESSAGE_CREATE,
            PushType.RELATIONSHIP_ADD,
            PushType.STAGE_INSTANCE_CREATE,
            -> true
            null -> false
        }

        override fun toString(): String = "PushPayload(type=$type, fields=<redacted>)"

        companion object {
            fun parse(data: Map<String, String>): PushPayload {
                val rawGuildId = data.longOrUnset("guild_id")
                val parentChannel = channel(
                    type = UNSET_INT,
                    guildId = rawGuildId,
                    name = data["parent_name"],
                    id = data.longOrUnset("parent_id"),
                    rtcRegion = data["rtc_region"],
                )
                val channel = channel(
                    type = data.intOrUnset("channel_type"),
                    guildId = rawGuildId,
                    name = data["channel_name"],
                    id = data.longOrUnset("channel_id"),
                    icon = data["channel_icon"],
                    rtcRegion = data["rtc_region"],
                )
                return PushPayload(
                    type = data["type"].orEmpty(),
                    messageId = data.longOrUnset("message_id"),
                    messageActivityType = data["message_activity_type"]?.toIntOrNull()?.let { MessageActivityType.fromApiInt(it) },
                    messageApplicationName = data["message_application_name"],
                    messageType = data.intOrUnset("message_type_"),
                    messageContent = data["message_content"],
                    parentChannel = parentChannel,
                    channel = channel,
                    acknowledgementChannelIds = parseAcknowledgementChannelIds(data["channel_ids"]),
                    userId = data.longOrUnset("user_id"),
                    userUsername = data["user_username"],
                    userDiscriminator = data["user_discriminator"]?.toIntOrNull(),
                    userAvatar = data["user_avatar"],
                    guildMemberAvatar = data["user_guild_avatar"],
                    relationshipType = data.intOrUnset("rel_type"),
                    deepLink = data["deeplink"],
                    guildId = when {
                        rawGuildId != UNSET_LONG -> rawGuildId
                        channel.type == Channel.DM || channel.type == Channel.GROUP_DM -> 0L
                        else -> UNSET_LONG
                    },
                    guildName = data["guild_name"],
                    guildIcon = data["guild_icon"],
                    activityType = data.intOrUnset("activity_type"),
                    activityName = data["activity_name"],
                    applicationId = data.longOrUnset("application_id"),
                    applicationName = data["application_name"],
                    applicationIcon = data["application_icon"],
                    sticker = parseSticker(data["message"]),
                    customTitle = data["title"],
                    subtitle = data["subtitle"],
                    titleResourceName = data["title_resource_name"],
                    subtitleResourceName = data["subtitle_resource_name"],
                    stageInstanceTopic = data["stage_instance_topic"],
                    guildScheduledEventEntityType = data["guild_scheduled_event_entity_type"]
                        ?.toIntOrNull()
                        ?.let(::guildScheduledEventEntityType),
                    guildScheduledEventId = data["guild_scheduled_event_id"]?.toLongOrNull(),
                    notificationType = PushNotificationType.fromWireValue(data["notification_type"]),
                    trackingType = data["tracking_type"],
                    notificationChannel = data["notification_channel"],
                    expandSubtitle = data["expand_subtitle"]?.toBoolean() ?: false,
                    notificationId = data["notification_id"],
                )
            }

            fun parseAcknowledgementChannelIds(value: String?): List<Long> = value.orEmpty()
                .split(',')
                .mapNotNull { it.toLongOrNull()?.takeUnless { id -> id == UNSET_LONG } }

            private fun Map<String, String>.intOrUnset(key: String) = this[key]?.toIntOrNull() ?: UNSET_INT

            private fun Map<String, String>.longOrUnset(key: String) = this[key]?.toLongOrNull() ?: UNSET_LONG

            private fun guildScheduledEventEntityType(value: Int) =
                GuildScheduledEventEntityType.entries.firstOrNull { it.apiValue == value }
                    ?: GuildScheduledEventEntityType.UNKNOWN

            private fun parseSticker(value: String?): Sticker? = value?.let { json ->
                runCatching {
                    GsonBuilder()
                        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                        .registerDiscordApiTypeAdapters()
                        .create()
                        .fromJson(json, com.discord.api.message.Message::class.java)
                        .stickers
                        ?.firstOrNull()
                }.getOrNull()
            }

            private fun channel(
                type: Int,
                guildId: Long,
                name: String?,
                id: Long,
                icon: String? = null,
                rtcRegion: String?,
            ) = Channel(
                topic = null,
                type = type,
                guildId = guildId,
                name = name,
                lastMessageId = 0L,
                id = id,
                ownerId = 0L,
                recipientIds = null,
                recipients = null,
                position = 0,
                permissionOverwrites = null,
                userLimit = 0,
                bitrate = 0,
                icon = icon,
                originChannelId = 0L,
                applicationId = 0L,
                nicks = null,
                nsfw = false,
                parentId = 0L,
                memberListId = null,
                rateLimitPerUser = 0,
                defaultAutoArchiveDuration = null,
                rtcRegion = rtcRegion,
                flags = null,
                guildHashes = null,
                availableTags = null,
                appliedTags = null,
                threadMetadata = null,
                messageCount = null,
                memberCount = null,
                memberIdsPreview = null,
                member = null,
            )
        }
    }

    companion object {
        const val UNSET_INT = -1
        const val UNSET_LONG = -1L
        private const val ACTIVITY_TYPE_STREAMING = 1

        fun parsePushPayload(data: Map<String, String>): PushPayload = PushPayload.parse(data)
    }
}

class NotificationCache(private val maxMessages: Int = 20) {
    data class Snapshot(val notificationId: Int, val messages: List<NotificationData>)

    private val channels = mutableMapOf<Long, MutableList<NotificationData>>()

    /**
     * Channels exempt from exactly one ack-driven clear.
     *
     * A direct reply acks the channel it replied to. Without this exemption that ack would also
     * cancel a notification that arrived between the reply being typed and the ack landing.
     */
    private val ignoreNextClearForAck = mutableSetOf<Long>()

    init { require(maxMessages > 0) }

    @Synchronized
    fun add(data: NotificationData): Snapshot {
        val messages = channels.getOrPut(data.channelId) { mutableListOf() }
        data.messageId?.let { id -> messages.removeAll { it.messageId == id } }
        messages += data
        if (messages.size > maxMessages) messages.subList(0, messages.size - maxMessages).clear()
        return Snapshot(notificationId(data.channelId), messages.toList())
    }

    /**
     * Drops the cached notifications for [channelId] and returns the notification id to cancel, or
     * null when nothing should be cancelled — including when an ack-driven clear was consumed by
     * the exemption set by [setIgnoreNextClearForAck].
     */
    @Synchronized
    fun removeChannel(channelId: Long, isAckRequest: Boolean = false): Int? {
        if (isAckRequest && ignoreNextClearForAck.remove(channelId)) return null
        ignoreNextClearForAck.remove(channelId)
        return channels.remove(channelId)?.let { notificationId(channelId) }
    }

    /**
     * Whether a clear for [channelId] should go ahead, consuming the ack exemption if one is set.
     *
     * Separate from [removeChannel] because a clear must still reach the system tray when the cache
     * is empty — a broadcast receiver woken into a fresh process has no cached notifications, and
     * the tray notification is still there.
     */
    @Synchronized
    fun consumeAckExemption(channelId: Long, isAckRequest: Boolean): Boolean {
        if (isAckRequest && ignoreNextClearForAck.remove(channelId)) return false
        ignoreNextClearForAck.remove(channelId)
        return true
    }

    @Synchronized
    fun setIgnoreNextClearForAck(channelId: Long, shouldIgnore: Boolean) {
        if (shouldIgnore) ignoreNextClearForAck += channelId else ignoreNextClearForAck -= channelId
    }

    @Synchronized
    fun get(channelId: Long): Snapshot? =
        channels[channelId]?.let { Snapshot(notificationId(channelId), it.toList()) }

    companion object {
        fun notificationId(channelId: Long): Int =
            ((channelId xor (channelId ushr 32)).toInt() and Int.MAX_VALUE).coerceAtLeast(1)
    }
}

internal sealed class NotificationCommand(val authSession: String?) {
    class MarkRead(val channelId: Long, val messageId: Long, session: String) : NotificationCommand(session)
    class Reply(val channelId: Long, val text: String, session: String) : NotificationCommand(session)
    class Delete(val channelId: Long) : NotificationCommand(null)
    class Cancel(val notificationId: Int) : NotificationCommand(null)
}

internal object NotificationActionSession {
    fun fromToken(token: String?): String? = token?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun matches(session: String?, token: String?): Boolean = session != null && session == fromToken(token)
}

internal object NotificationActionValidator {
    fun validate(
        action: String?,
        channelId: Long,
        messageId: Long,
        notificationId: Int,
        authSession: String?,
        reply: CharSequence? = null,
    ): NotificationCommand? {
        return when (action) {
            NotificationActionReceiver.ACTION_MARK_READ ->
                authSession?.takeIf { it.isNotBlank() && channelId > 0 && messageId > 0 }
                    ?.let { NotificationCommand.MarkRead(channelId, messageId, it) }
            NotificationActionReceiver.ACTION_REPLY -> reply?.toString()?.takeIf { it.isNotBlank() && it.length <= 2_000 }
                ?.let { text -> authSession?.takeIf { it.isNotBlank() && channelId > 0 }
                    ?.let { NotificationCommand.Reply(channelId, text, it) } }
            NotificationActionReceiver.ACTION_DELETE ->
                if (channelId > 0) NotificationCommand.Delete(channelId) else null
            NotificationActionReceiver.ACTION_CANCEL ->
                if (notificationId > 0) NotificationCommand.Cancel(notificationId) else null
            else -> null
        }
    }
}
