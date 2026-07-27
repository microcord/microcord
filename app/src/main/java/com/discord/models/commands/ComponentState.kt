package com.discord.models.commands

/**
 * Marker for the per-component state a message's interactive components can be in.
 *
 * The only variant in 126.21 is [ActionComponentStoreState]; the hierarchy is sealed so the
 * component views can exhaust it.
 */
sealed class ComponentState
