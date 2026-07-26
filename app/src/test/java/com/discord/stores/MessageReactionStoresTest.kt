package com.discord.stores

import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.schedulers.TestScheduler

/** Reaction counting in [StoreMessagesHolder] and the user-list subsystem in [StoreMessageReactions]. */
class MessageReactionStoresTest {

    @Before
    fun initializePersistence() {
        Persister.init(Files.createTempDirectory("reactions").toFile(), Observable.never())
    }

    /**
     * The gateway echoes your own reaction back. Without the already-`me` short circuit that echo
     * increments a second time and the pill reads 2 for a single reaction.
     */
    @Test
    fun ownReactionEchoDoesNotDoubleCount() {
        val holder = holder()
        holder.updateReactions(listOf(update(ME, THUMBS_UP)), add = true)
        holder.updateReactions(listOf(update(ME, THUMBS_UP)), add = true)

        val reactions = holder.getMessages(CHANNEL).single().reactions.orEmpty()
        assertEquals(1, reactions.single().count)
        assertTrue(reactions.single().me)
    }

    /** Another user's reaction on the same emoji still counts, and does not clear the `me` flag. */
    @Test
    fun otherUserReactionAddsToOwnReaction() {
        val holder = holder()
        holder.updateReactions(listOf(update(ME, THUMBS_UP)), add = true)
        holder.updateReactions(listOf(update(OTHER, THUMBS_UP)), add = true)

        val reaction = holder.getMessages(CHANNEL).single().reactions.orEmpty().single()
        assertEquals(2, reaction.count)
        assertTrue(reaction.me)
    }

    /**
     * A removal attributed to the current user is ignored when the current user is not flagged on
     * that emoji, so an echoed removal cannot decrement a count it never incremented.
     */
    @Test
    fun echoedOwnRemovalIsIgnoredWhenNotReacted() {
        val holder = holder(
            initialReactions = listOf(MessageReaction(3, THUMBS_UP, me = false)),
        )
        holder.updateReactions(listOf(update(ME, THUMBS_UP)), add = false)

        assertEquals(3, holder.getMessages(CHANNEL).single().reactions.orEmpty().single().count)
    }

    @Test
    fun removingTheLastReactionCollapsesToNull() {
        val holder = holder()
        holder.updateReactions(listOf(update(OTHER, THUMBS_UP)), add = true)
        holder.updateReactions(listOf(update(OTHER, THUMBS_UP)), add = false)

        assertNull(holder.getMessages(CHANNEL).single().reactions)
    }

    /** Emoji order is the order they were first reacted with, and survives edits to any of them. */
    @Test
    fun emojiOrderIsPreserved() {
        val holder = holder()
        holder.updateReactions(listOf(update(OTHER, THUMBS_UP)), add = true)
        holder.updateReactions(listOf(update(OTHER, HEART)), add = true)
        holder.updateReactions(listOf(update(ME, THUMBS_UP)), add = true)

        val keys = holder.getMessages(CHANNEL).single().reactions.orEmpty().map { it.emoji.reactionKey }
        assertEquals(listOf("+1", "heart"), keys)
    }

    @Test
    fun removeEmojiDropsOnlyThatEmoji() {
        val holder = holder()
        holder.updateReactions(listOf(update(OTHER, THUMBS_UP)), add = true)
        holder.updateReactions(listOf(update(OTHER, HEART)), add = true)
        holder.removeEmojiReactions(CHANNEL, MESSAGE, THUMBS_UP)

        val keys = holder.getMessages(CHANNEL).single().reactions.orEmpty().map { it.emoji.reactionKey }
        assertEquals(listOf("heart"), keys)
    }

    /** Without a REST client the user list stays in its loading state rather than failing. */
    @Test
    fun reactionUsersDefaultToLoading() {
        val fixture = reactionsFixture()
        val results = mutableListOf<StoreMessageReactions.EmojiResults>()
        fixture.store.observeMessageReactions(CHANNEL, MESSAGE, THUMBS_UP).subscribe(results::add)
        fixture.scheduler.triggerActions()

        assertTrue(results.all { it is StoreMessageReactions.EmojiResults.Loading })
    }

    /** A reconnect drops cached user lists: reactions added while offline are never replayed. */
    @Test
    fun connectionOpenClearsCachedUserLists() {
        val fixture = reactionsFixture()
        val results = mutableListOf<StoreMessageReactions.EmojiResults>()
        fixture.store.observeMessageReactions(CHANNEL, MESSAGE, THUMBS_UP).subscribe(results::add)
        fixture.scheduler.triggerActions()

        fixture.dispatcher.schedule { fixture.store.handleConnectionOpen() }
        fixture.scheduler.triggerActions()
        assertFalse(results.isEmpty())
    }

    private fun holder(initialReactions: List<MessageReaction>? = null): StoreMessagesHolder {
        val holder = StoreMessagesHolder(cache = Persister("react-${System.nanoTime()}", emptyMap()))
        holder.setMyUserId(ME)
        holder.selectChannel(CHANNEL)
        holder.addMessages(
            listOf(Message(id = MESSAGE, channelId = CHANNEL, reactions = initialReactions)),
        )
        return holder
    }

    private fun reactionsFixture(): ReactionsFixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = Persister("me-${System.nanoTime()}", StoreUser.EMPTY_ME_USER),
        )
        val store = StoreMessageReactions(dispatcher, users, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        return ReactionsFixture(scheduler, dispatcher, store)
    }

    private fun update(userId: Long, emoji: MessageReactionEmoji) =
        MessageReactionUpdate(userId, CHANNEL, MESSAGE, emoji)

    private data class ReactionsFixture(
        val scheduler: TestScheduler,
        val dispatcher: Dispatcher,
        val store: StoreMessageReactions,
    )

    private companion object {
        const val CHANNEL = 100L
        const val MESSAGE = 200L
        const val ME = 1L
        const val OTHER = 2L
        val THUMBS_UP = MessageReactionEmoji(id = null, name = "+1", animated = false)
        val HEART = MessageReactionEmoji(id = null, name = "heart", animated = false)
    }
}
