package com.discord.stores

import com.discord.api.user.User
import com.discord.api.message.MessagePoll
import com.discord.api.message.PollAnswer
import com.discord.api.message.PollAnswerCount
import com.discord.api.message.PollMedia
import com.discord.api.message.PollResults
import com.discord.api.message.PollVoteEvent
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.schedulers.TestScheduler

class StorePinnedMessagesTest {

    @Test
    fun failedLoadStaysEmptyAndRetriesWhileSuccessPreservesApiOrder() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val callbacks = mutableListOf<(Result<StorePinnedMessages.Page>) -> Unit>()
        val store = StorePinnedMessages(dispatcher, deck, loader = { _, _, callback -> callbacks += callback })
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        val observed = mutableListOf<List<Message>>()
        val first = store.observeForChannel(CHANNEL_ID).subscribe(observed::add)
        assertEquals(listOf(emptyList<Message>()), observed)

        callbacks.single()(Result.failure(IllegalStateException("fixture failure")))
        scheduler.triggerActions()
        first.unsubscribe()

        store.observeForChannel(CHANNEL_ID).subscribe(observed::add)
        assertEquals(2, callbacks.size)
        callbacks.last()(Result.success(page(message(1), message(2))))
        scheduler.triggerActions()

        assertEquals(listOf(1L, 2L), observed.last().map(Message::id))
    }

    @Test
    fun pollVotesUpdateThePinnedSnapshot() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        lateinit var callback: (Result<StorePinnedMessages.Page>) -> Unit
        val store = StorePinnedMessages(dispatcher, deck, loader = { _, _, loaded -> callback = loaded })
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val observed = mutableListOf<List<Message>>()
        store.observeForChannel(CHANNEL_ID).subscribe(observed::add)
        callback(Result.success(page(message(1).copy(poll = poll()))))
        scheduler.triggerActions()

        dispatcher.schedule {
            store.handlePollVote(PollVoteEvent(7, CHANNEL_ID, 1, answerId = 1), true, 7)
        }
        scheduler.triggerActions()

        assertEquals(1, observed.last().single().poll!!.results!!.answerCounts.single().count)
    }

    private fun message(id: Long) = Message(id, CHANNEL_ID, author = User(id, "user$id"))

    private fun page(vararg messages: Message, hasMore: Boolean = false) = StorePinnedMessages.Page(
        messages.mapIndexed { index, message ->
            StorePinnedMessages.Pin("2024-01-0${index + 1}T00:00:00.000Z", message)
        },
        hasMore,
    )

    private fun poll() = MessagePoll(
        question = PollMedia("Question"),
        answers = listOf(PollAnswer(1, PollMedia("Answer"))),
        expiry = null,
        allowMultiselect = false,
        layoutType = 1,
        results = PollResults(false, listOf(PollAnswerCount(1, 0, false))),
    )

    private companion object {
        const val CHANNEL_ID = 10L
    }
}
