package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildForumPost
import com.discord.api.message.MessageFlags
import com.discord.models.message.Message
import com.discord.utilities.SnowflakeUtils
import java.util.Calendar
import kotlin.math.abs

/**
 * Reference `isNewMessageGroup`, ported condition for condition and in the same order.
 *
 * Shared by both renderers — the live list in `WidgetChatListModelMessages` and the
 * pinned/search/mention lists in [ChatListRows] — so a message groups the same way wherever it is
 * shown. Everything is compared against the message that *started* the group rather than the one
 * immediately before it, so a group can never span more than [MESSAGE_GROUP_INTERVAL_MS] end to end.
 *
 * Note what is deliberately absent: attachments, embeds and stickers have no bearing on grouping.
 * An image sent between two lines of text belongs to the same block as the text around it.
 */
object MessageGrouping {
    const val MESSAGE_GROUP_INTERVAL_MS = 7 * 60 * 1000L

    fun isNewMessageGroup(
        channel: Channel?,
        parentChannel: Channel?,
        groupStart: Message?,
        message: Message,
        groupStartBlocked: Boolean = false,
        messageBlocked: Boolean = false,
    ): Boolean {
        if (groupStart == null) return true

        // A spine that does not reach the author's avatar reads as a rendering fault.
        if (message.hasFlag(MessageFlags.HAS_THREAD) || isCommandType(message)) return true
        if (groupStartBlocked != messageBlocked) return true

        val type = message.type ?: TYPE_DEFAULT
        if (type > TYPE_DEFAULT) {
            if (!isSystem(groupStart)) return true
            return type == REPLY_MESSAGE_TYPE
        }
        if (isSystem(groupStart)) return true

        val authorId = groupStart.author?.id
        if (authorId == null || authorId != message.author?.id) return true
        if (groupStart.hasFlag(MessageFlags.EPHEMERAL) != message.hasFlag(MessageFlags.EPHEMERAL)) return true
        // Only the username override, and only when the incoming message is the webhook's.
        if (message.webhookId != null && groupStart.author?.username != message.author?.username) return true
        if (channel != null && channel.isGuildForumPost(parentChannel) && groupStart.id == channel.id) return true
        if (!sameDay(timestamp(groupStart), timestamp(message))) return true
        if (abs(timestamp(message) - timestamp(groupStart)) >= MESSAGE_GROUP_INTERVAL_MS) return true

        val groupStartSilent = groupStart.hasFlag(MessageFlags.SUPPRESS_NOTIFICATIONS)
        val messageSilent = message.hasFlag(MessageFlags.SUPPRESS_NOTIFICATIONS)
        if (messageSilent && !groupStartSilent) return true
        // A silent group only breaks for something that would have notified.
        if (groupStartSilent && !messageSilent && mentionsAnyone(message)) return true

        return message.applicationId != groupStart.applicationId
    }

    fun timestamp(message: Message) =
        message.timestamp?.dateTimeMillis ?: snowflakeTimestamp(message.id)

    fun sameDay(first: Long, second: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = first }
        val b = Calendar.getInstance().apply { timeInMillis = second }
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    private fun snowflakeTimestamp(id: Long) =
        if (id > 4_194_304L) SnowflakeUtils.toTimestamp(id) else 0L

    private fun mentionsAnyone(message: Message) = !message.mentions.isNullOrEmpty() ||
        !message.mentionRoles.isNullOrEmpty() ||
        message.mentionEveryone == true

    private fun isCommandType(message: Message) =
        message.type == TYPE_CHAT_INPUT_COMMAND || message.type == TYPE_CONTEXT_MENU_COMMAND

    /** Reference `isSystemMessage`; a pending or rejected send is still the user's own message. */
    private fun isSystem(message: Message) =
        message.type !in USER_MESSAGE_TYPES && !message.isLocal() && !message.isFailed()

    private const val TYPE_DEFAULT = 0
    private const val REPLY_MESSAGE_TYPE = 19
    private const val TYPE_CHAT_INPUT_COMMAND = 20
    private const val TYPE_CONTEXT_MENU_COMMAND = 23

    /** `MessageTypesSets.USER_MESSAGE`; everything else counts as a system message here. */
    private val USER_MESSAGE_TYPES = setOf(0, 19, 20, 23, 26, 41, 45, 47, 68)
}
