package com.discord.utilities.theme

import com.discord.models.domain.ModelUserSettings
import com.google.gson.JsonElement

/**
 * A user theme in Aliucord's JSON format, plus two sections of our own.
 *
 * Every Aliucord section means what it means to Aliucord's Themer, so its themes import unchanged:
 * `manifest`, `background`, `fonts`, `raws`, `simple_colors`, `colors` and `drawable_tints`.
 * Ours are `attrs`, which overrides theme attributes directly by name, and `microcord`, which picks
 * the built-in theme the overrides sit on and how the background shows.
 *
 * Unknown top-level keys are kept verbatim in [extras], so re-exporting a theme loses nothing.
 */
data class ThemeDefinition(
    val manifest: Manifest,
    val base: String = BASE_DEFAULT,
    val simpleColors: Map<String, Int> = emptyMap(),
    val colors: Map<String, Int> = emptyMap(),
    val attrs: Map<String, Int> = emptyMap(),
    /** Drawable resource name to tint. */
    val drawableTints: Map<String, Int> = emptyMap(),
    val background: Background? = null,
    /** Font name, or [ALL_FONTS], to a font file's `https://` or `file://` location. */
    val fonts: Map<String, String> = emptyMap(),
    /** Sound (`raw` resource) name to an audio file's `https://` or `file://` location. */
    val raws: Map<String, String> = emptyMap(),
    /** How the background shows; `null` picks one from the colors ([effectiveTransparency]). */
    val transparency: Transparency? = null,
    /**
     * Material You colors, which follow the wallpaper and so cannot be stored as numbers.
     *
     * Keyed `section.key` (`simple_colors.accent`); the value is an `android.R.color` name such as
     * `system_accent1_500`.
     */
    val systemColors: Map<String, String> = emptyMap(),
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    data class Manifest(
        val name: String,
        val author: String = DEFAULT_AUTHOR,
        val version: String = DEFAULT_VERSION,
        val license: String? = null,
        val updater: String? = null,
    )

    /** Aliucord's `background`: an image, a black scrim of [overlayAlpha] (0-255) over it, and a blur. */
    data class Background(
        val url: String,
        val overlayAlpha: Int = DEFAULT_OVERLAY_ALPHA,
        val blurRadius: Float = 0f,
    )

    /** Aliucord Themer's transparency modes: where the background image shows. */
    enum class Transparency(val wire: String) {
        NONE("none"),
        CHAT("chat"),
        CHAT_SETTINGS("chat_settings"),
        FULL("full");

        companion object {
            fun of(wire: String?) = values().firstOrNull { it.wire == wire }
        }
    }

    /**
     * Without an explicit mode, a theme whose main background is see-through was made for the whole
     * app to show the image (Aliucord's full transparency); otherwise only the chat does.
     */
    val effectiveTransparency: Transparency
        get() = when {
            background == null -> Transparency.NONE
            transparency != null -> transparency
            listOfNotNull(simpleColors["background"], attrs["colorBackgroundPrimary"]).any { it ushr 24 < 0xFF } ->
                Transparency.FULL
            else -> Transparency.CHAT
        }

    /** A copy with every [systemColors] reference filled in by [resolve]; unresolvable ones are dropped. */
    fun withSystemColors(resolve: (String) -> Int?): ThemeDefinition {
        if (systemColors.isEmpty()) return this
        val simple = simpleColors.toMutableMap()
        val palette = colors.toMutableMap()
        val themeAttrs = attrs.toMutableMap()
        val tints = drawableTints.toMutableMap()
        systemColors.forEach { (path, name) ->
            val color = resolve(name) ?: return@forEach
            val section = path.substringBefore('.')
            val key = path.substringAfter('.')
            when (section) {
                SECTION_SIMPLE_COLORS -> simple[key] = color
                SECTION_COLORS -> palette[key] = color
                SECTION_ATTRS -> themeAttrs[key] = color
                SECTION_DRAWABLE_TINTS -> tints[key] = color
            }
        }
        return copy(simpleColors = simple, colors = palette, attrs = themeAttrs, drawableTints = tints, systemColors = emptyMap())
    }

    private fun definesColor(path: String): Boolean {
        val key = path.substringAfter('.')
        return when (path.substringBefore('.')) {
            SECTION_SIMPLE_COLORS -> key in simpleColors
            SECTION_COLORS -> key in colors
            SECTION_ATTRS -> key in attrs
            SECTION_DRAWABLE_TINTS -> key in drawableTints
            else -> false
        }
    }

    companion object {
        const val DEFAULT_AUTHOR = "Anonymous"
        const val DEFAULT_VERSION = "1.0.0"
        const val BASE_DEFAULT = ModelUserSettings.THEME_DARK
        const val DEFAULT_OVERLAY_ALPHA = 150
        const val MAX_BLUR_RADIUS = 25f

        /** Aliucord's `fonts` key for every font at once. */
        const val ALL_FONTS = "*"

        const val SECTION_SIMPLE_COLORS = "simple_colors"
        const val SECTION_COLORS = "colors"
        const val SECTION_ATTRS = "attrs"
        const val SECTION_DRAWABLE_TINTS = "drawable_tints"

        /** Built-in themes a custom theme may sit on. Material You is excluded: its colors are the wallpaper's. */
        val BASES = listOf(
            ModelUserSettings.THEME_DARK,
            ModelUserSettings.THEME_DARKER,
            ModelUserSettings.THEME_MIDNIGHT,
            ModelUserSettings.THEME_LIGHT,
        )

        /** The fonts Aliucord themes can replace, by resource name. [ALL_FONTS] replaces all of them. */
        val FONTS = listOf(
            "ginto_bold", "ginto_medium", "ginto_regular", "roboto_medium_numbers", "sourcecodepro_semibold",
            "whitney_bold", "whitney_medium", "whitney_semibold",
        )

        /** The sounds Aliucord themes can replace, by `raw` resource name. */
        val SOUNDS = listOf(
            "call_calling", "call_ringing", "deafen", "mute", "undeafen", "unmute", "reconnect", "stream_ended",
            "stream_started", "stream_user_joined", "stream_user_left", "user_join", "user_leave", "user_moved",
        )

        /**
         * Layers [themes] the way Aliucord applies several enabled themes: in order, later ones
         * winning key by key. The manifest is the last theme's.
         */
        fun layer(themes: List<ThemeDefinition>): ThemeDefinition = themes.reduce { lower, upper ->
            upper.copy(
                base = if (upper.base != BASE_DEFAULT) upper.base else lower.base,
                simpleColors = lower.simpleColors + upper.simpleColors,
                colors = lower.colors + upper.colors,
                attrs = lower.attrs + upper.attrs,
                drawableTints = lower.drawableTints + upper.drawableTints,
                background = upper.background ?: lower.background,
                fonts = lower.fonts + upper.fonts,
                raws = lower.raws + upper.raws,
                transparency = upper.transparency ?: lower.transparency,
                // A plain color in the upper theme also replaces a lower Material You reference.
                systemColors = lower.systemColors.filterKeys { !upper.definesColor(it) } + upper.systemColors,
                extras = lower.extras + upper.extras,
            )
        }
    }
}
