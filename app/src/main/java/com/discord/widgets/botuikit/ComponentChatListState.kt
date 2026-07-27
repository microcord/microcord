package com.discord.widgets.botuikit

import com.discord.api.botuikit.SelectItem
import com.discord.stores.StoreApplicationInteractions
import com.discord.stores.StoreStream
import rx.Observable

/**
 * The store-side inputs the chat list needs to turn a message's API components into renderable
 * [com.discord.models.botuikit.MessageComponent]s.
 *
 * The reference combines interaction progress, optimistic select-menu state, and the animated
 * emoji preference into one map keyed by message id.
 */
object ComponentChatListState {

    fun observeChatListComponentState(): Observable<Map<Long, ComponentStoreState>> =
        Observable.combineLatest(
            StoreStream.getInteractions().observeComponentInteractionState(),
            StoreStream.getLocalActionComponentState().observeSelectComponentSelections(),
            StoreStream.getUserSettings().observeIsAnimatedEmojisEnabled(),
            ::combineState,
        )

    internal fun combineState(
        interactions: Map<Long, Map<Int, StoreApplicationInteractions.InteractionSendState>>,
        selections: Map<Long, Map<Int, List<SelectItem>>>,
        animateEmojis: Boolean,
    ): Map<Long, ComponentStoreState> = (interactions.keys + selections.keys).associateWith { messageId ->
        ComponentStoreState(interactions[messageId], selections[messageId], animateEmojis)
    }

    /**
     * One message's component state.
     *
     * Both maps are keyed by the component's index within the message's flattened component tree,
     * which is the same index [com.discord.models.botuikit.MessageComponent.index] carries.
     */
    data class ComponentStoreState(
        val interactionState: Map<Int, StoreApplicationInteractions.InteractionSendState>? = emptyMap(),
        val selections: Map<Int, List<SelectItem>>? = emptyMap(),
        val animateEmojis: Boolean = true,
    )
}
