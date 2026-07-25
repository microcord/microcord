package com.discord.utilities.notifications

enum class NotificationKind { MESSAGE, DIRECT_MESSAGE, SOCIAL, GENERAL }

enum class NotificationSource { LOCAL, GATEWAY }

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
)

class NotificationCache(private val maxMessages: Int = 20) {
    data class Snapshot(val notificationId: Int, val messages: List<NotificationData>)

    private val channels = mutableMapOf<Long, MutableList<NotificationData>>()

    init { require(maxMessages > 0) }

    @Synchronized
    fun add(data: NotificationData): Snapshot {
        val messages = channels.getOrPut(data.channelId) { mutableListOf() }
        data.messageId?.let { id -> messages.removeAll { it.messageId == id } }
        messages += data
        if (messages.size > maxMessages) messages.subList(0, messages.size - maxMessages).clear()
        return Snapshot(notificationId(data.channelId), messages.toList())
    }

    @Synchronized
    fun removeChannel(channelId: Long): Int? =
        channels.remove(channelId)?.let { notificationId(channelId) }

    @Synchronized
    fun get(channelId: Long): Snapshot? =
        channels[channelId]?.let { Snapshot(notificationId(channelId), it.toList()) }

    companion object {
        fun notificationId(channelId: Long): Int =
            ((channelId xor (channelId ushr 32)).toInt() and Int.MAX_VALUE).coerceAtLeast(1)
    }
}

internal sealed class NotificationCommand(val authGeneration: Int) {
    class MarkRead(val channelId: Long, val messageId: Long, generation: Int) : NotificationCommand(generation)
    class Reply(val channelId: Long, val text: String, generation: Int) : NotificationCommand(generation)
    class Delete(val channelId: Long, generation: Int) : NotificationCommand(generation)
    class Cancel(val notificationId: Int, generation: Int) : NotificationCommand(generation)
}

internal object NotificationActionValidator {
    fun validate(
        action: String?,
        channelId: Long,
        messageId: Long,
        notificationId: Int,
        authGeneration: Int,
        reply: CharSequence? = null,
    ): NotificationCommand? {
        if (authGeneration < 0) return null
        return when (action) {
            NotificationActionReceiver.ACTION_MARK_READ ->
                if (channelId > 0 && messageId > 0) NotificationCommand.MarkRead(channelId, messageId, authGeneration) else null
            NotificationActionReceiver.ACTION_REPLY -> reply?.toString()?.takeIf { it.isNotBlank() && it.length <= 2_000 }
                ?.let { if (channelId > 0) NotificationCommand.Reply(channelId, it, authGeneration) else null }
            NotificationActionReceiver.ACTION_DELETE ->
                if (channelId > 0) NotificationCommand.Delete(channelId, authGeneration) else null
            NotificationActionReceiver.ACTION_CANCEL ->
                if (notificationId > 0) NotificationCommand.Cancel(notificationId, authGeneration) else null
            else -> null
        }
    }
}
