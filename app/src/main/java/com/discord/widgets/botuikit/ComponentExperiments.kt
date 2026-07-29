package com.discord.widgets.botuikit

import com.discord.api.botuikit.ComponentType

interface ComponentExperiments {
    fun isEnabled(componentType: ComponentType): Boolean
}
