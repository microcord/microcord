package com.discord.widgets.settings.themes

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import androidx.annotation.StringRes
import androidx.appcompat.view.ContextThemeWrapper
import com.discord.R
import com.discord.utilities.theme.ThemeLinks

/**
 * Everything the theme editor can recolor.
 *
 * Nothing is hardcoded but the grouping. The list covers every theme attribute that resolves to a
 * color on the chosen base, plus every plain `<color>` resource. Both are read from the `R` classes,
 * so a resource added to the app shows up here without touching this file. Color state lists are
 * left out: one color cannot stand in for their states.
 */
internal object ThemeTokens {

    enum class Kind { ATTR, COLOR }

    enum class Group(@get:StringRes val title: Int) {
        /** Not a token group: the editor lists what the theme itself changes here, ahead of everything. */
        CHANGED(R.string.microcord_theme_group_changed),
        SURFACES(R.string.microcord_theme_group_surfaces),
        TEXT(R.string.microcord_theme_group_text),
        INTERACTIVE(R.string.microcord_theme_group_interactive),
        BRAND(R.string.microcord_theme_group_brand),
        BUTTONS(R.string.microcord_theme_group_buttons),
        STATUS(R.string.microcord_theme_group_status),
        CHAT(R.string.microcord_theme_group_chat),
        LEGACY(R.string.microcord_theme_group_legacy),
        OTHER(R.string.microcord_theme_group_other),
        PALETTE(R.string.microcord_theme_group_palette),
        LIBRARIES(R.string.microcord_theme_group_libraries),
    }

    /** [base] is the color on the built-in base theme, before any custom theme. */
    class Token(val kind: Kind, val name: String, val group: Group, val base: Int) {
        val key = "${kind.name.lowercase()}:$name"
    }

    /** The tokens people reach for first, in this order, ahead of the rest of their group. */
    private val PRIORITY = listOf(
        "colorBackgroundPrimary", "colorBackgroundSecondary", "colorBackgroundTertiary", "colorBackgroundFloating",
        "colorBackgroundSecondaryAlt", "colorBackgroundAccent", "colorChatInputBackground", "colorTabsBackground",
        "colorBackgroundModifierSelected", "colorBackgroundModifierAccent", "colorTextNormal", "colorTextMuted",
        "colorHeaderPrimary", "colorHeaderSecondary", "colorTextLink", "colorTextBrand", "colorTextDanger",
        "colorInteractiveNormal", "colorInteractiveActive", "colorInteractiveMuted", "colorChannelDefault",
        "colorChannelItemSelected", "color_brand", "color_brand_500", "colorControlBrandForeground",
        "colorControlActivated", "theme_chat_mentioned_me", "theme_chat_mention_background",
        "theme_chat_mention_foreground",
    ).withIndex().associate { it.value to it.index }

    private val LIBRARY_PREFIXES = listOf(
        "abc_", "mtrl_", "m3_", "material_", "design_", "common_google", "notification_", "cardview_",
        "browser_actions", "androidx_", "cpv_", "ucrop_", "exo_", "test_", "tooltip_", "switch_thumb",
        "button_material", "background_material", "bright_foreground", "dim_foreground", "foreground_material",
        "highlighted_text", "hint_", "primary_text_", "secondary_text_", "ripple_material", "error_color_material",
        "accent_material", "background_floating", "primary_material", "primary_dark_material",
    )

    /** Reflection and resolution over a few thousand ids: run it once per editor, off the main thread. */
    fun load(context: Context, base: String): List<Token> {
        val themed = ContextThemeWrapper(context.applicationContext, ThemeLinks.baseStyle(base))
        val resources = themed.resources
        val value = TypedValue()
        val attrs = fields(R.attr::class.java).mapNotNull { (name, id) ->
            if (!themed.theme.resolveAttribute(id, value, true) || !value.isColor()) return@mapNotNull null
            Token(Kind.ATTR, name, groupOf(name), value.data)
        }
        val colors = fields(R.color::class.java).mapNotNull { (name, id) ->
            if (!isPlainColor(resources, id, value)) return@mapNotNull null
            val group = if (LIBRARY_PREFIXES.any(name::startsWith)) Group.LIBRARIES else Group.PALETTE
            Token(Kind.COLOR, name, group, value.data)
        }
        return (attrs + colors).sortedWith(compareBy<Token>({ it.group.ordinal }, { PRIORITY[it.name] ?: Int.MAX_VALUE }, { it.name }))
    }

    /** The app's own drawables, by name, for the icon tint page. Library drawables are left out. */
    fun drawableNames(): List<String> =
        fields(R.drawable::class.java).map { it.first }.filterNot { name -> LIBRARY_PREFIXES.any(name::startsWith) }.sorted()

    /** `colorBackgroundPrimary` → "Background primary"; `theme_chat_code` → "Chat code". */
    fun label(name: String): String {
        val words = name.removePrefix("color").removePrefix("theme_").removePrefix("_")
            .replace('_', ' ')
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .trim().lowercase()
        return words.replaceFirstChar { it.titlecase() }.ifEmpty { name }
    }

    private fun groupOf(name: String): Group = when {
        name.startsWith("colorBackground") || name.startsWith("colorSurface") || name.startsWith("colorTabsBackground") ||
            name.startsWith("colorChatInput") || name == "colorInputBackground" || name == "toolbarColor" ||
            name == "colorPrimary" || name == "colorPrimaryDark" -> Group.SURFACES
        name.startsWith("colorText") || name.startsWith("colorHeader") -> Group.TEXT
        name.startsWith("colorInteractive") || name.startsWith("colorControl") || name.startsWith("colorChannel") ||
            name.startsWith("flexInputIcon") -> Group.INTERACTIVE
        name.startsWith("color_brand") || name == "colorAccent" -> Group.BRAND
        name.startsWith("colorButton") -> Group.BUTTONS
        name.startsWith("colorStatus") || name.startsWith("colorInfo") -> Group.STATUS
        name.startsWith("theme_") -> Group.CHAT
        name.startsWith("primary_") -> Group.LEGACY
        else -> Group.OTHER
    }

    private fun fields(type: Class<*>): List<Pair<String, Int>> = type.fields.mapNotNull { field ->
        runCatching { field.name to field.getInt(null) }.getOrNull()
    }

    private fun isPlainColor(resources: Resources, id: Int, value: TypedValue): Boolean =
        runCatching { resources.getValue(id, value, true) }.isSuccess && value.isColor()

    private fun TypedValue.isColor() = type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
}
