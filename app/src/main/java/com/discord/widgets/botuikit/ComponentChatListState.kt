package com.discord.widgets.botuikit

import com.discord.api.botuikit.SelectItem
import com.discord.stores.StoreApplicationInteractions

/**
 * The store-side inputs the chat list needs to turn a message's API components into renderable
 * [com.discord.models.botuikit.MessageComponent]s.
 *
 * The reference also exposes `observeChatListComponentState()` here — a `combineLatest` of
 * `StoreApplicationInteractions.observeComponentInteractionState()`,
 * `StoreLocalActionComponentState.observeSelectComponentSelections()` and
 * `StoreUserSettings.observeIsAnimatedEmojisEnabled()`, projected into one
 * `Map<messageId, ComponentStoreState>`. Both stores exist in this reconstruction but neither is
 * published by `StoreStream`, so the observable has no way to reach them yet and is deliberately
 * not written rather than faked; see the handoff notes.
 */
object ComponentChatListState {

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
