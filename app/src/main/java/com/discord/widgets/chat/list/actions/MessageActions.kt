package com.discord.widgets.chat.list.actions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import java.io.Serializable

object MessageActions {
    enum class Action {
        REPLY, EDIT, DELETE, COPY, PIN, UNPIN, ADD_REACTION, RETRY, CANCEL,
        PROFILE, MANAGE_REACTIONS, REMOVE_ALL_REACTIONS, MARK_UNREAD, COPY_ID, REPORT,
        FORWARD,
    }

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
        val canEdit: Boolean? = null,
        val canDelete: Boolean? = null,
        val canManageMessages: Boolean = false,
        val canAddReactions: Boolean? = null,
        val canTogglePinned: Boolean? = null,
        val canMarkUnread: Boolean = false,
        val hasReactions: Boolean = false,
        val isDeveloper: Boolean = false,
        val isFailed: Boolean = false,
        val canForward: Boolean = false,
    ) : Serializable

    fun evaluate(input: Input): Map<Action, Availability> {
        val own = input.authorId == input.currentUserId
        val privateChannel = input.channelType == Channel.DM || input.channelType == Channel.GROUP_DM
        val userMessage = input.messageType == 0 || input.messageType == 19
        val canSend = privateChannel || input.has(Permission.SEND_MESSAGES)
        val canManage = input.has(Permission.MANAGE_MESSAGES)
        val canPin = privateChannel ||
            input.has(Permission.PIN_MESSAGES) && input.has(Permission.READ_MESSAGE_HISTORY)
        val remote = !input.isLocal

        return mapOf(
            Action.REPLY to Availability(remote && userMessage, canSend),
            Action.EDIT to Availability(input.canEdit ?: (remote && userMessage && own)),
            Action.DELETE to Availability(input.canDelete ?: (remote && (own || canManage))),
            Action.COPY to Availability(input.hasContent),
            Action.PIN to Availability(remote && !input.pinned && (input.canTogglePinned ?: canPin)),
            Action.UNPIN to Availability(remote && input.pinned && (input.canTogglePinned ?: canPin)),
            Action.ADD_REACTION to Availability(
                input.canAddReactions ?: remote,
                input.canAddReactions ?: (privateChannel || input.has(Permission.ADD_REACTIONS)),
            ),
            Action.RETRY to Availability(input.isLocal && input.canResend),
            Action.CANCEL to Availability(input.isLocal),
            Action.PROFILE to Availability(input.authorId != null),
            Action.MANAGE_REACTIONS to Availability(input.hasReactions),
            Action.REMOVE_ALL_REACTIONS to Availability(input.hasReactions && input.canManageMessages),
            Action.MARK_UNREAD to Availability(!input.isFailed && input.canMarkUnread),
            Action.COPY_ID to Availability(input.isDeveloper && remote),
            Action.REPORT to Availability(input.authorId != null && !own),
            Action.FORWARD to Availability(input.canForward),
        )
    }

    private fun Input.has(permission: Long): Boolean {
        val value = permissions ?: return false
        return value and Permission.ADMINISTRATOR != 0L || value and permission != 0L
    }
}
