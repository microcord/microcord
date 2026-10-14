package com.discord.utilities.theme

import com.discord.models.domain.ModelUserSettings

/**
 * Flattens a theme into the two override sets the runtime resource table carries: theme attributes
 * and palette colors, both by name.
 *
 * Precedence follows Aliucord's load order, lowest first:
 * 1. `simple_colors`, in file order.
 * 2. `colors`.
 * 3. Our own `attrs`.
 *
 * A palette color also repaints the attributes that held its original value. That replays
 * Aliucord's by-value swap; see [AliucordMapping.attrsForColor]. It only happens on a dark base,
 * because those values came from the dark theme.
 *
 * Pure, so mapping fixes reach themes that were imported before them.
 */
object ThemeResolver {

    /** [tints] maps a drawable resource name to the color it is tinted. */
    data class Resolved(
        val attrs: Map<String, Int>,
        val colors: Map<String, Int>,
        val warnings: List<String>,
        val tints: Map<String, Int> = emptyMap(),
    )

    fun resolve(theme: ThemeDefinition): Resolved {
        val attrs = LinkedHashMap<String, Int>()
        val colors = LinkedHashMap<String, Int>()
        val tints = LinkedHashMap<String, Int>()
        val warnings = mutableListOf<String>()
        val darkBase = theme.base != ModelUserSettings.THEME_LIGHT

        fun setColor(name: String, color: Int) {
            val leaf = AliucordMapping.SELECTOR_LEAVES[name] ?: name
            colors[leaf] = color
            AliucordMapping.COLOR_TINTS[name]?.forEach { tints[it] = color }
            if (darkBase) AliucordMapping.attrsForColor(leaf).forEach { attrs[it] = color }
        }

        for ((key, color) in theme.simpleColors) {
            val rule = AliucordMapping.SIMPLE[key]
            if (rule == null) {
                warnings += "simple_colors.$key is not a known key"
                continue
            }
            rule.colors.forEach { setColor(it.name, it.apply(color)) }
            rule.attrs.forEach { attrs[it.name] = it.apply(color) }
            rule.tints.forEach { tints[it] = color }
        }
        theme.colors.forEach { (name, color) -> setColor(name, color) }
        attrs.putAll(theme.attrs)
        tints.putAll(theme.drawableTints)
        return Resolved(attrs, colors, warnings, tints)
    }
}
