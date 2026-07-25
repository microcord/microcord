package com.discord.widgets.chat.list.actions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import java.io.Serializable

object MessageActions {
    enum class Action { REPLY, EDIT, DELETE, COPY, PIN, UNPIN, ADD_REACTION, RETRY, CANCEL }

    data class Availability(val visible: Boolean, val enabled: Boolean = visible)

    data class Input(
        val authorId: Long?,
        val currentUserId: Long,
        val messageType: Int?,
        val isLocal: Boolean,
        val canResend: Boolean,
        val hasContent: Boolean,
        val permissions: Long?,
        val pinned: Boolean,
        val channelType: Int,
    ) : Serializable

    fun evaluate(input: Input): Map<Action, Availability> {
        val own = input.authorId == input.currentUserId
        val privateChannel = input.channelType == Channel.DM || input.channelType == Channel.GROUP_DM
        val userMessage = input.messageType == 0 || input.messageType == 19
        val canSend = privateChannel || input.has(Permission.SEND_MESSAGES)
        val canManage = input.has(Permission.MANAGE_MESSAGES)
        val canPin = privateChannel || canManage
        val remote = !input.isLocal

        return mapOf(
            Action.REPLY to Availability(remote && userMessage, canSend),
            Action.EDIT to Availability(remote && userMessage && own),
            Action.DELETE to Availability(remote && (own || canManage)),
            Action.COPY to Availability(input.hasContent),
            Action.PIN to Availability(remote && !input.pinned, canPin),
            Action.UNPIN to Availability(remote && input.pinned, canPin),
            Action.ADD_REACTION to Availability(remote, privateChannel || input.has(Permission.ADD_REACTIONS)),
            Action.RETRY to Availability(input.isLocal && input.canResend),
            Action.CANCEL to Availability(input.isLocal),
        )
    }

    private fun Input.has(permission: Long): Boolean {
        val value = permissions ?: return false
        return value and Permission.ADMINISTRATOR != 0L || value and permission != 0L
    }
}
