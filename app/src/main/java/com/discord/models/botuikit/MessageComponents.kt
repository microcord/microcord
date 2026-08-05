package com.discord.models.botuikit

import com.discord.api.botuikit.*
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
    val skuId: Long? = null,
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
    val defaultValues: List<SelectDefaultValue> = emptyList(),
) : ActionMessageComponent()

data class SectionMessageComponent(
    override val type: ComponentType,
    override val index: Int,
    val id: Int?,
    val components: List<MessageComponent>,
    val accessory: MessageComponent?,
) : LayoutMessageComponent() {
    override val children: List<MessageComponent> get() = components + listOfNotNull(accessory)
}

data class TextDisplayMessageComponent(
    override val type: ComponentType,
    override val index: Int,
    val id: Int?,
    val content: String,
) : MessageComponent

data class ThumbnailMessageComponent(
    override val type: ComponentType,
    override val index: Int,
    val id: Int?,
    val media: UnfurledMediaItem,
    val description: String?,
    val spoiler: Boolean,
) : MessageComponent

data class MediaGalleryMessageComponent(
    override val type: ComponentType,
    override val index: Int,
    val id: Int?,
    val items: List<MediaGalleryItem>,
) : MessageComponent

data class FileMessageComponent(
    override val type: ComponentType,
    override val index: Int,
    val id: Int?,
    val file: UnfurledMediaItem,
    val name: String,
    val size: Long,
    val spoiler: Boolean,
) : MessageComponent

data class SeparatorMessageComponent(
    override val type: ComponentType,
    override val index: Int,
    val divider: Boolean,
    val spacing: Int,
) : MessageComponent

data class ContainerMessageComponent(
    override val type: ComponentType,
    override val index: Int,
    val id: Int?,
    override val children: List<MessageComponent>,
    val accentColor: Int?,
    val spoiler: Boolean,
) : LayoutMessageComponent()

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
        // A premium button routes to a purchase surface this build has no counterpart for.
        disabled || style == ButtonStyle.PREMIUM -> ActionInteractionComponentState.Disabled
        url != null -> ActionInteractionComponentState.Enabled
        else -> buttonStateInteraction
    },
    customId = customId,
    label = label,
    style = style,
    emoji = emoji,
    url = url,
    skuId = skuId,
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
): SelectMessageComponent {
    val options = options.orEmpty()
    return SelectMessageComponent(
    type = type,
    index = index,
    stateInteraction = if (disabled) {
        ActionInteractionComponentState.Disabled
    } else {
        actionInteractionComponentState
    },
    customId = customId,
    placeholder = placeholder,
    minValues = minValues ?: DEFAULT_SELECT_VALUES,
    maxValues = maxValues ?: DEFAULT_SELECT_VALUES,
    options = options,
    selectedOptions = componentStoreState.selections?.get(index)
        ?: options.filter { it.default },
    emojiAnimationsEnabled = componentStoreState.animateEmojis,
    defaultValues = defaultValues.orEmpty(),
)
}

fun SectionComponent.mergeToMessageComponent(
    index: Int,
    children: List<MessageComponent>,
): SectionMessageComponent = SectionMessageComponent(
    type,
    index,
    id,
    children.dropLast(1),
    children.lastOrNull(),
)

fun TextDisplayComponent.mergeToMessageComponent(index: Int) =
    TextDisplayMessageComponent(type, index, id, content)

fun ThumbnailComponent.mergeToMessageComponent(index: Int) =
    ThumbnailMessageComponent(type, index, id, media, description, spoiler == true)

fun MediaGalleryComponent.mergeToMessageComponent(index: Int) =
    MediaGalleryMessageComponent(type, index, id, items)

fun FileComponent.mergeToMessageComponent(index: Int) =
    FileMessageComponent(type, index, id, file, name, size, spoiler == true)

fun SeparatorComponent.mergeToMessageComponent(index: Int) =
    SeparatorMessageComponent(type, index, divider != false, spacing ?: 1)

fun ContainerComponent.mergeToMessageComponent(index: Int, children: List<MessageComponent>) =
    ContainerMessageComponent(type, index, id, children, accentColor, spoiler == true)

fun LayoutMessageComponent.childError(): ActionInteractionComponentState.Failed? =
    children.asSequence()
        .filterIsInstance<ActionMessageComponent>()
        .map(ActionMessageComponent::stateInteraction)
        .filterIsInstance<ActionInteractionComponentState.Failed>()
        .firstOrNull()

fun MessageComponent.disabledForForward(): MessageComponent = when (this) {
    is ButtonMessageComponent -> copy(stateInteraction = ActionInteractionComponentState.Disabled)
    is SelectMessageComponent -> copy(stateInteraction = ActionInteractionComponentState.Disabled)
    is ActionRowMessageComponent -> copy(components = components.map(MessageComponent::disabledForForward))
    is SectionMessageComponent -> copy(
        components = components.map(MessageComponent::disabledForForward),
        accessory = accessory?.disabledForForward(),
    )
    is ContainerMessageComponent -> copy(children = children.map(MessageComponent::disabledForForward))
    else -> this
}

/** `min_values` and `max_values` are both optional and both default to one. */
private const val DEFAULT_SELECT_VALUES = 1
