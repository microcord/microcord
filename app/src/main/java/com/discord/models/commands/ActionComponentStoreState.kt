package com.discord.models.commands

import com.discord.stores.StoreApplicationInteractions

/**
 * The store-derived state of one action component (a button or a select menu) on a message.
 *
 * The three flags are not mutually exclusive on purpose: while *any* component of a message is
 * [loading], every *other* component of that message is [disabled], and a component that already
 * came back with an error keeps its [failed] message while the user retries a sibling.
 */
data class ActionComponentStoreState(
    val loading: Boolean,
    val failed: StoreApplicationInteractions.InteractionSendState.Failed?,
    val disabled: Boolean,
) : ComponentState()
