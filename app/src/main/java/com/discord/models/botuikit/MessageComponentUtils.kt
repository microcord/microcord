package com.discord.models.botuikit

/** Component helpers shared by the chat list rows and the interaction stores. */
object MessageComponentUtils {

    /**
     * The first failure among a row's action components, so the row can render one error under the
     * whole group instead of one per button.
     */
    fun getChildError(component: LayoutMessageComponent): ActionInteractionComponentState.Failed? =
        component.childError()
}
