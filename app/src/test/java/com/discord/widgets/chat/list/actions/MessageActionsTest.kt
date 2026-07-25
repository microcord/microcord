package com.discord.widgets.chat.list.actions

import com.discord.api.channel.Channel
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.permission.Permission
import com.discord.models.message.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionsTest {
    @Test
    fun policySeparatesOwnershipPermissionsPinsAndLocalActions() {
        val own = policy(authorId = 7, permissions = Permission.SEND_MESSAGES or Permission.ADD_REACTIONS)
        assertTrue(own.getValue(MessageActions.Action.EDIT).enabled)
        assertTrue(own.getValue(MessageActions.Action.DELETE).enabled)
        assertFalse(own.getValue(MessageActions.Action.PIN).enabled)
        assertTrue(own.getValue(MessageActions.Action.ADD_REACTION).enabled)

        val moderator = policy(authorId = 8, permissions = Permission.MANAGE_MESSAGES)
        assertFalse(moderator.getValue(MessageActions.Action.EDIT).visible)
        assertTrue(moderator.getValue(MessageActions.Action.DELETE).enabled)
        assertTrue(moderator.getValue(MessageActions.Action.PIN).enabled)

        val local = policy(authorId = 7, local = true, canResend = true)
        assertTrue(local.getValue(MessageActions.Action.RETRY).enabled)
        assertTrue(local.getValue(MessageActions.Action.CANCEL).enabled)
        assertFalse(local.getValue(MessageActions.Action.REPLY).visible)
    }

    @Test
    fun reactionRoutesAndRollbackRestoreExactPreviousState() {
        val unicode = MessageReactionEmoji(null, "🔥", false)
        val custom = MessageReactionEmoji("42", "party", false)
        assertEquals("%F0%9F%94%A5", MessageActionCoordinator.reactionRoute(unicode))
        assertEquals("party%3A42", MessageActionCoordinator.reactionRoute(custom))

        val previous = listOf(MessageReaction(2, unicode, false))
        val updates = mutableListOf<Message>()
        var complete: ((Result<Unit>) -> Unit)? = null
        val coordinator = coordinator(
            updated = updates::add,
            reaction = { _, _, _, _, callback -> complete = callback },
        )
        coordinator.toggleReaction(Message(1, channelId = 2, reactions = previous), unicode)
        assertEquals(MessageReaction(3, unicode, true), updates.first().reactions?.single())
        complete!!(Result.failure(IllegalStateException("offline")))
        assertEquals(previous, updates.last().reactions)
    }

    @Test
    fun pinDeleteRetryAndCancelUseInjectedStoreHooks() {
        val updated = mutableListOf<Message>()
        val deleted = mutableListOf<Pair<Long, Long>>()
        val retried = mutableListOf<Message>()
        val cancelled = mutableListOf<Message>()
        var pinCompletion: ((Result<Unit>) -> Unit)? = null
        val coordinator = coordinator(
            updated = updated::add,
            deleted = { channelId, messageId -> deleted += channelId to messageId },
            retry = retried::add,
            cancel = cancelled::add,
            pin = { _, _, _, done -> pinCompletion = done },
        )
        val message = Message(9, channelId = 3, pinned = false, type = -2, nonce = "local")

        coordinator.setPinned(message, true)
        pinCompletion!!(Result.failure(IllegalStateException("offline")))
        coordinator.delete(message)
        coordinator.retry(message)
        coordinator.cancel(message)

        assertEquals(listOf(true, false), updated.map(Message::pinned))
        assertEquals(listOf(3L to 9L), deleted)
        assertEquals(listOf(message), retried)
        assertEquals(listOf(message), cancelled)
    }

    private fun policy(
        authorId: Long?,
        permissions: Long? = null,
        local: Boolean = false,
        canResend: Boolean = false,
    ) = MessageActions.evaluate(MessageActions.Input(
        authorId, 7, if (local) -2 else 0, local, canResend, true,
        permissions, false, Channel.GUILD_TEXT,
    ))

    private fun coordinator(
        updated: (Message) -> Unit = {},
        deleted: (Long, Long) -> Unit = { _, _ -> },
        retry: (Message) -> Unit = {},
        cancel: (Message) -> Unit = {},
        reaction: (Long, Long, String, Boolean, (Result<Unit>) -> Unit) -> Unit = { _, _, _, _, done -> done(Result.success(Unit)) },
        pin: (Long, Long, Boolean, (Result<Unit>) -> Unit) -> Unit = { _, _, _, done -> done(Result.success(Unit)) },
    ) = MessageActionCoordinator(MessageActionCoordinator.Callbacks(
        deleteRemote = { _, _, done -> done(Result.success(Unit)) },
        editRemote = { _, _, _, done -> done(Result.success(Unit)) },
        toggleReactionRemote = reaction,
        setPinnedRemote = pin,
        messageUpdated = updated,
        messageDeleted = deleted,
        createPendingReply = { _, _, _, _ -> },
        retryLocal = retry,
        cancelLocal = cancel,
    ))
}
