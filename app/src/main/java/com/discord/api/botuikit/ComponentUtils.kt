package com.discord.api.botuikit

/** Traversal helpers for the nested component tree attached to a message. */
object ComponentUtils {
    /**
     * Visits [this] and every component nested inside a [LayoutComponent], depth first, handing
     * each one a running index that continues across nesting levels.
     *
     * Returns the index after the last visited component, so callers can keep numbering across
     * several trees.
     */
    fun List<Component>.forEachComponentIndexed(
        startIndex: Int = 0,
        action: (index: Int, component: Component) -> Unit,
    ): Int {
        var index = startIndex
        for (component in this) {
            action(index, component)
            index++
            if (component is LayoutComponent) {
                index = component.components.forEachComponentIndexed(index, action)
            }
        }
        return index
    }
}
