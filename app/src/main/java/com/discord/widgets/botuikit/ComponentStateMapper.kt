package com.discord.widgets.botuikit

import com.discord.api.botuikit.ActionComponent
import com.discord.api.botuikit.ActionRowComponent
import com.discord.api.botuikit.ButtonComponent
import com.discord.api.botuikit.Component
import com.discord.api.botuikit.LayoutComponent
import com.discord.api.botuikit.SelectComponent
import com.discord.models.botuikit.ActionInteractionComponentState
import com.discord.models.botuikit.MessageComponent
import com.discord.models.botuikit.mergeToMessageComponent
import com.discord.stores.StoreApplicationInteractions
import com.discord.widgets.botuikit.ComponentChatListState.ComponentStoreState

object ComponentStateMapper {
    fun createActionMessageComponent(
        component: ActionComponent,
        index: Int,
        storeState: ComponentStoreState,
        experimentController: ComponentExperiments,
    ): MessageComponent? {
        if (!experimentController.isEnabled(component.type)) return null

        val loadingIndex = storeState.interactionState
            ?.entries
            ?.firstOrNull { it.value is StoreApplicationInteractions.InteractionSendState.Loading }
            ?.key
        val failed = storeState.interactionState?.get(index)
            as? StoreApplicationInteractions.InteractionSendState.Failed
        val interactionState = when {
            loadingIndex == index -> ActionInteractionComponentState.Loading
            loadingIndex != null -> ActionInteractionComponentState.Disabled
            failed != null -> ActionInteractionComponentState.Failed(failed.errorMessage)
            else -> ActionInteractionComponentState.Enabled
        }

        return when (component) {
            is ButtonComponent -> component.mergeToMessageComponent(index, interactionState, storeState)
            is SelectComponent -> component.mergeToMessageComponent(index, interactionState, storeState)
            else -> null
        }
    }

    fun toMessageLayoutComponent(
        component: LayoutComponent,
        index: Int,
        children: List<MessageComponent>,
        experimentController: ComponentExperiments,
    ): MessageComponent? {
        if (!experimentController.isEnabled(component.type)) return null
        return (component as? ActionRowComponent)?.mergeToMessageComponent(index, children)
    }

    fun processComponentsToMessageComponents(
        apiComponents: List<Component>,
        componentStoreState: ComponentStoreState?,
        componentExperiments: ComponentExperiments,
        animateEmojis: Boolean,
    ): List<MessageComponent> {
        val storeState = componentStoreState ?: ComponentStoreState(animateEmojis = animateEmojis)
        var componentIndex = 0

        fun map(components: List<Component>): List<MessageComponent> = components.mapNotNull { component ->
            val index = componentIndex++
            when (component) {
                is ActionComponent -> createActionMessageComponent(
                    component,
                    index,
                    storeState,
                    componentExperiments,
                )
                is LayoutComponent -> toMessageLayoutComponent(
                    component,
                    index,
                    map(component.components),
                    componentExperiments,
                )
                else -> null
            }
        }

        return map(apiComponents)
    }

    data class ChildComponents(
        val layoutComponentIndex: Int,
        val parentLayoutComponent: LayoutComponent? = null,
        var currentComponentInsertionIndex: Int = 0,
        val total: Int,
    ) {
        val components: Array<MessageComponent?> = arrayOfNulls(total)
    }
}
