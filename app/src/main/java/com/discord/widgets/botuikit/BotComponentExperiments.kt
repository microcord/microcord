package com.discord.widgets.botuikit

import com.discord.api.botuikit.ComponentType

/** Every component type this build knows how to render is rendered; only unknown ones are dropped. */
object BotComponentExperiments : ComponentExperiments {
    override fun isEnabled(componentType: ComponentType): Boolean = componentType != ComponentType.UNKNOWN
}
