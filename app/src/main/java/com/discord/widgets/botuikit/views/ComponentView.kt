package com.discord.widgets.botuikit.views

import com.discord.api.botuikit.ComponentType
import com.discord.models.botuikit.MessageComponent
import com.discord.widgets.botuikit.ComponentProvider

interface ComponentView<T : MessageComponent> {
    fun configure(component: T, componentProvider: ComponentProvider, componentActionListener: ComponentActionListener)
    fun type(): ComponentType
}
