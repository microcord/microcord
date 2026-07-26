package com.discord.api.interaction

import com.discord.api.botuikit.Component
import com.discord.api.commands.Application
import java.io.Serializable

/**
 * Payload of `INTERACTION_CREATE`, `INTERACTION_SUCCESS` and `INTERACTION_FAILURE`.
 *
 * [nonce] correlates the event back to the local command invocation that produced it.
 */
data class InteractionStateUpdate(
    val id: Long?,
    val nonce: String,
)

/** Payload of `INTERACTION_MODAL_CREATE`. */
data class InteractionModalCreate(
    val id: Long,
    val customId: String,
    val application: Application,
    val title: String,
    val components: List<Component>,
    val nonce: String,
) : Serializable
