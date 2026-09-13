package com.discord.widgets.search.results

import com.discord.stores.StoreChat.InteractionState
import com.discord.widgets.search.SearchStore
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSearchResultsTest {
    @Test
    fun scrollingToTheBottomRequestsTheNextPageUsingTheLatestHit() {
        val requests = mutableListOf<Long>()
        val store = Proxy.newProxyInstance(
            SearchStore::class.java.classLoader, arrayOf(SearchStore::class.java),
        ) { _, method, args ->
            check(method.name == "loadMore")
            requests += args!!.single() as Long
            null
        } as SearchStore
        val handler = WidgetSearchResults.SearchResultAdapterEventHandler(store) {}
        val bottom = InteractionState(0L, 0L, true, InteractionState.SCROLLED_BOTTOM)

        handler.onInteractionStateUpdated(bottom) // No loaded hits yet.
        handler.onOldestMessageId(0L, 100L)
        handler.onInteractionStateUpdated(bottom.copy(isTouchedSinceLastJump = false))
        handler.onInteractionStateUpdated(InteractionState(0L, 0L, true, InteractionState.SCROLLED_TOP))
        handler.onInteractionStateUpdated(InteractionState(0L, 0L, true, null)) // Detached handlers.
        assertEquals(emptyList<Long>(), requests)

        handler.onInteractionStateUpdated(bottom)
        handler.onOldestMessageId(0L, 80L)
        handler.onInteractionStateUpdated(bottom)
        for (id in listOf(0L, -1L, Long.MAX_VALUE)) {
            handler.onOldestMessageId(0L, id)
            handler.onInteractionStateUpdated(bottom)
        }
        assertEquals(listOf(100L, 80L), requests)
    }
}
