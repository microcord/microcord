package com.discord.models.botuikit

import com.discord.api.botuikit.ButtonStyle
import com.discord.api.botuikit.ComponentEmoji
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.SelectItem

interface MessageComponent {
    val type: ComponentType
    val index: Int
}

abstract class ActionMessageComponent : MessageComponent {
    abstract val stateInteraction: ActionInteractionComponentState
}

abstract class LayoutMessageComponent : MessageComponent {
    abstract val children: List<MessageComponent>
}

sealed class ActionInteractionComponentState {
    data object Enabled : ActionInteractionComponentState()
    data object Loading : ActionInteractionComponentState()
    data class Failed(val errorMessage: String?) : ActionInteractionComponentState()
    data object Disabled : ActionInteractionComponentState()
}

data class ActionRowMessageComponent(
    override val type: ComponentType,
    override val index: Int = 0,
    val components: List<MessageComponent>,
) : LayoutMessageComponent() {
    override val children: List<MessageComponent> get() = components
}

data class ButtonMessageComponent(
    override val type: ComponentType,
    override val index: Int,
    override val stateInteraction: ActionInteractionComponentState = ActionInteractionComponentState.Enabled,
    val customId: String?,
    val label: String?,
    val style: ButtonStyle,
    val emoji: ComponentEmoji? = null,
    val url: String? = null,
    val emojiAnimationsEnabled: Boolean,
) : ActionMessageComponent()

data class SelectMessageComponent(
    override val type: ComponentType,
    override val index: Int,
    override val stateInteraction: ActionInteractionComponentState,
    val customId: String,
    val placeholder: String?,
    val minValues: Int,
    val maxValues: Int,
    val options: List<SelectItem>,
    val selectedOptions: List<SelectItem>,
    val emojiAnimationsEnabled: Boolean,
) : ActionMessageComponent()

fun LayoutMessageComponent.childError(): ActionInteractionComponentState.Failed? =
    children.asSequence()
        .filterIsInstance<ActionMessageComponent>()
        .map(ActionMessageComponent::stateInteraction)
        .filterIsInstance<ActionInteractionComponentState.Failed>()
        .firstOrNull()
