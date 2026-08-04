package com.discord.widgets.botuikit

import android.content.Context
import android.view.ViewGroup
import com.discord.api.botuikit.ComponentType
import com.discord.models.botuikit.MessageComponent
import com.discord.widgets.botuikit.views.ActionRowComponentView
import com.discord.widgets.botuikit.views.ButtonComponentView
import com.discord.widgets.botuikit.views.ComponentView
import com.discord.widgets.botuikit.views.select.SelectComponentView

class ComponentInflater(val context: Context) {
    fun inflateComponent(
        component: ComponentType,
        root: ViewGroup,
    ): ComponentView<out MessageComponent>? = when (component) {
        ComponentType.ACTION_ROW -> ActionRowComponentView.inflateComponent(context, root)
        ComponentType.BUTTON -> ButtonComponentView.inflateComponent(context, root)
        ComponentType.SELECT, ComponentType.USER_SELECT, ComponentType.ROLE_SELECT,
        ComponentType.MENTIONABLE_SELECT, ComponentType.CHANNEL_SELECT,
        -> SelectComponentView.inflateComponent(context, root)
        ComponentType.TEXT, ComponentType.UNKNOWN -> null
    }

    companion object {
        fun from(context: Context) = ComponentInflater(context)
    }
}
