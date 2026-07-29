package com.discord.widgets.chat.list.actions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionsTest {
    @Test
    fun moderationAndReactionRowsFollowContext() {
        val actions = MessageActions.evaluate(
            input(
                canDelete = true,
                canManageMessages = true,
                canAddReactions = true,
                canTogglePinned = true,
                canMarkUnread = true,
                hasReactions = true,
                isDeveloper = true,
            ),
        )

        assertTrue(actions.getValue(MessageActions.Action.DELETE).visible)
        assertTrue(actions.getValue(MessageActions.Action.MANAGE_REACTIONS).visible)
        assertTrue(actions.getValue(MessageActions.Action.REMOVE_ALL_REACTIONS).visible)
        assertTrue(actions.getValue(MessageActions.Action.MARK_UNREAD).visible)
        assertTrue(actions.getValue(MessageActions.Action.COPY_ID).visible)
        assertTrue(actions.getValue(MessageActions.Action.REPORT).visible)
    }

    @Test
    fun localMessageOnlyOffersRetryAndCancel() {
        val actions = MessageActions.evaluate(input(isLocal = true, canResend = true))

        assertTrue(actions.getValue(MessageActions.Action.RETRY).visible)
        assertTrue(actions.getValue(MessageActions.Action.CANCEL).visible)
        assertFalse(actions.getValue(MessageActions.Action.ADD_REACTION).visible)
        assertFalse(actions.getValue(MessageActions.Action.COPY_ID).visible)
    }

    private fun input(
        isLocal: Boolean = false,
        canResend: Boolean = false,
        canDelete: Boolean? = null,
        canManageMessages: Boolean = false,
        canAddReactions: Boolean? = null,
        canTogglePinned: Boolean? = null,
        canMarkUnread: Boolean = false,
        hasReactions: Boolean = false,
        isDeveloper: Boolean = false,
    ) = MessageActions.Input(
        authorId = 2L,
        currentUserId = 1L,
        messageType = 0,
        isLocal = isLocal,
        canResend = canResend,
        hasContent = true,
        permissions = Permission.SEND_MESSAGES or Permission.ADD_REACTIONS,
        pinned = false,
        channelType = Channel.GUILD_TEXT,
        canDelete = canDelete,
        canManageMessages = canManageMessages,
        canAddReactions = canAddReactions,
        canTogglePinned = canTogglePinned,
        canMarkUnread = canMarkUnread,
        hasReactions = hasReactions,
        isDeveloper = isDeveloper,
    )
}
