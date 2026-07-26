package com.discord.stores

import com.discord.models.message.Message
import com.discord.stores.StoreChat.InteractionState
import com.discord.stores.updates.ObservationDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreChatTest {

    /**
     * The edge flags are gated on the list having been touched. Right after a jump the list is
     * positioned programmatically, and treating that position as user intent would immediately
     * page away from the message that was jumped to.
     */
    @Test
    fun edgeFlagsRequireTouchSinceLastJump() {
        val untouched = InteractionState(1, 2, false, TOP or BOTTOM)
        assertFalse(untouched.isAtTop)
        assertFalse(untouched.isAtBottom)
        assertTrue(untouched.isAtTopIgnoringTouch())
        assertTrue(untouched.isAtBottomIgnoringTouch())

        val touched = untouched.copy(isTouchedSinceLastJump = true)
        assertTrue(touched.isAtTop)
        assertTrue(touched.isAtBottom)
    }

    @Test
    fun scrollStateBitsAreIndependent() {
        val nearBottomOnly = InteractionState(1, 2, true, NEAR_BOTTOM)
        assertFalse(nearBottomOnly.isAtBottomIgnoringTouch())
        assertFalse(nearBottomOnly.isAtTopIgnoringTouch())
        assertTrue(nearBottomOnly.isNearBottomIgnoringTouch())

        val nothing = InteractionState(1, 2, true, InteractionState.SCROLLED)
        assertFalse(nothing.isAtTopIgnoringTouch())
        assertFalse(nothing.isAtBottomIgnoringTouch())
        assertFalse(nothing.isNearBottomIgnoringTouch())
    }

    /** A null layout manager means "no list yet", which is neither edge. */
    @Test
    fun noLayoutManagerIsNotAtAnyEdge() {
        assertEquals(InteractionState.SCROLLED, InteractionState.getScrollState(null))
    }

    @Test
    fun interactionStateAndEditingMessageArePublishedAfterDispatch() {
        val fixture = fixture()
        val states = mutableListOf<InteractionState>()
        val editing = mutableListOf<StoreChat.EditingMessage?>()
        fixture.store.observeInteractionState().subscribe(states::add)
        fixture.store.observeEditingMessage().subscribe(editing::add)

        fixture.store.setInteractionState(InteractionState(7, 70, true, BOTTOM))
        fixture.store.setEditingMessage(StoreChat.EditingMessage(Message(id = 5), "half typed"))
        fixture.scheduler.triggerActions()

        assertEquals(listOf(7L), states.map { it.channelId })
        assertEquals("half typed", editing.last()?.content)
    }

    /** Events are one-shot: a subscriber that attaches afterwards must not replay them. */
    @Test
    fun chatEventsAreNotReplayed() {
        val fixture = fixture()
        val seen = mutableListOf<StoreChat.Event>()
        fixture.store.observeEvents().subscribe(seen::add)
        fixture.store.replaceChatText("/shrug")
        assertEquals(listOf(StoreChat.Event.ReplaceChatText("/shrug ")), seen)

        val late = mutableListOf<StoreChat.Event>()
        fixture.store.observeEvents().subscribe(late::add)
        assertTrue(late.isEmpty())
    }

    @Test
    fun blockedMessageGroupExpansionToggles() {
        val fixture = fixture()
        fixture.store.toggleBlockedMessageGroup(99)
        fixture.scheduler.triggerActions()
        assertEquals(listOf(99L), fixture.store.getExpandedBlockedMessageGroups())

        fixture.store.toggleBlockedMessageGroup(99)
        fixture.scheduler.triggerActions()
        assertTrue(fixture.store.getExpandedBlockedMessageGroups().isEmpty())
    }

    private fun fixture(): Fixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreChat(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        return Fixture(scheduler, dispatcher, store)
    }

    private data class Fixture(
        val scheduler: TestScheduler,
        val dispatcher: Dispatcher,
        val store: StoreChat,
    )

    private companion object {
        const val BOTTOM = InteractionState.SCROLLED_BOTTOM
        const val TOP = InteractionState.SCROLLED_TOP
        const val NEAR_BOTTOM = InteractionState.SCROLLED_NEAR_BOTTOM
    }
}
