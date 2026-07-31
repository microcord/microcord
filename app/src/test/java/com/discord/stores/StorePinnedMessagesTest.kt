package com.discord.stores

import com.discord.api.user.User
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
        val callbacks = mutableListOf<(Result<List<Message>>) -> Unit>()
        val store = StorePinnedMessages(dispatcher, deck) { _, callback -> callbacks += callback }
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        val observed = mutableListOf<List<Message>>()
        val first = store.observeForChannel(CHANNEL_ID).subscribe(observed::add)
        assertEquals(listOf(emptyList<Message>()), observed)

        callbacks.single()(Result.failure(IllegalStateException("fixture failure")))
        scheduler.triggerActions()
        first.unsubscribe()

        store.observeForChannel(CHANNEL_ID).subscribe(observed::add)
        assertEquals(2, callbacks.size)
        callbacks.last()(Result.success(listOf(message(1), message(2))))
        scheduler.triggerActions()

        assertEquals(listOf(1L, 2L), observed.last().map(Message::id))
    }

    private fun message(id: Long) = Message(id, CHANNEL_ID, author = User(id, "user$id"))

    private companion object {
        const val CHANNEL_ID = 10L
    }
}
