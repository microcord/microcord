package com.discord.models.botuikit

import com.discord.api.botuikit.ActionRowComponent
import com.discord.api.botuikit.ButtonComponent
import com.discord.api.botuikit.ButtonStyle
import com.discord.api.botuikit.ComponentEmoji
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.SelectComponent
import com.discord.api.botuikit.SelectItem
import com.discord.widgets.botuikit.ComponentChatListState.ComponentStoreState

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

/**
 * Projects an API action row onto its renderable form.
 *
 * The row itself carries no store state — [children] have already been merged individually — so
 * this only attaches the row's position within the message.
 */
fun ActionRowComponent.mergeToMessageComponent(
    index: Int,
    children: List<MessageComponent>,
): ActionRowMessageComponent = ActionRowMessageComponent(type, index, children)

/**
 * Projects an API button onto its renderable form.
 *
 * [buttonStateInteraction] is the state the interaction store derived for this index, but it is
 * overridden twice: a button the application marked `disabled` is always
 * [ActionInteractionComponentState.Disabled], and a link button (one carrying a [ButtonComponent.url])
 * is always [ActionInteractionComponentState.Enabled] because tapping it never produces an
 * interaction to be loading or failing.
 */
fun ButtonComponent.mergeToMessageComponent(
    index: Int,
    buttonStateInteraction: ActionInteractionComponentState,
    componentStoreState: ComponentStoreState,
): ButtonMessageComponent = ButtonMessageComponent(
    type = type,
    index = index,
    stateInteraction = when {
        disabled -> ActionInteractionComponentState.Disabled
        url != null -> ActionInteractionComponentState.Enabled
        else -> buttonStateInteraction
    },
    customId = customId,
    label = label,
    style = style,
    emoji = emoji,
    url = url,
    emojiAnimationsEnabled = componentStoreState.animateEmojis,
)

/**
 * Projects an API select menu onto its renderable form.
 *
 * The user's own selection wins when the store holds one for this index; otherwise the menu falls
 * back to the options the application marked [SelectItem.default]. Unlike a button, a link state
 * does not exist here, so only `disabled` overrides [actionInteractionComponentState].
 */
fun SelectComponent.mergeToMessageComponent(
    index: Int,
    actionInteractionComponentState: ActionInteractionComponentState,
    componentStoreState: ComponentStoreState,
): SelectMessageComponent = SelectMessageComponent(
    type = type,
    index = index,
    stateInteraction = if (disabled) {
        ActionInteractionComponentState.Disabled
    } else {
        actionInteractionComponentState
    },
    customId = customId,
    placeholder = placeholder,
    minValues = minValues,
    maxValues = maxValues,
    options = options,
    selectedOptions = componentStoreState.selections?.get(index)
        ?: options.filter { it.default },
    emojiAnimationsEnabled = componentStoreState.animateEmojis,
)

fun LayoutMessageComponent.childError(): ActionInteractionComponentState.Failed? =
    children.asSequence()
        .filterIsInstance<ActionMessageComponent>()
        .map(ActionMessageComponent::stateInteraction)
        .filterIsInstance<ActionInteractionComponentState.Failed>()
        .firstOrNull()
