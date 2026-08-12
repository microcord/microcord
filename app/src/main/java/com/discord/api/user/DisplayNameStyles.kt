package com.discord.api.user

import java.io.Serializable

/**
 * How a name is drawn: a font, an effect and the colours the effect uses.
 *
 * Carried by both a user and a guild member; a member's copy replaces the user's outright rather
 * than merging with it, so a per-server style overrides every field at once.
 *
 * Reads and writes disagree on shape. The server returns this object under `display_name_styles`,
 * but a `PATCH` sends the three fields flat — `display_name_font_id`, `display_name_effect_id`,
 * `display_name_colors` — which is why there is no request body counterpart here.
 *
 * @param colors RGB values with no alpha, decimal on the wire. How many are meaningful depends on
 *   the effect ([colorCountFor]); an empty list means "use the theme's normal text colour".
 */
data class DisplayNameStyles(
    val fontId: Int = FONT_DEFAULT,
    val effectId: Int = EFFECT_SOLID,
    val colors: List<Int> = emptyList(),
) : Serializable {

    companion object {
        const val FONT_BANGERS = 1
        const val FONT_BIO_RHYME = 2
        const val FONT_CHERRY_BOMB = 3
        const val FONT_CHICLE = 4
        const val FONT_COMPAGNON = 5
        const val FONT_MUSEO_MODERNO = 6
        const val FONT_NEO_CASTEL = 7
        const val FONT_PIXELIFY = 8
        const val FONT_RIBES = 9
        const val FONT_SINISTRE = 10
        const val FONT_DEFAULT = 11
        const val FONT_ZILLA_SLAB = 12
        const val FONT_PLAYPEN_SANS = 13
        const val FONT_ORBITRON = 14
        const val FONT_NEW_ROCKER = 15
        const val FONT_KALAM = 16

        const val EFFECT_SOLID = 1
        const val EFFECT_GRADIENT = 2
        const val EFFECT_NEON = 3
        const val EFFECT_TOON = 4
        const val EFFECT_POP = 5

        /** Retired: it has no renderer of its own and draws as [EFFECT_SOLID]. */
        const val EFFECT_GLOW = 6
        const val EFFECT_PRISM = 7
        const val EFFECT_GUMMY = 8

        /** How many of [colors] an effect reads. */
        fun colorCountFor(effectId: Int): Int = when (effectId) {
            EFFECT_GRADIENT, EFFECT_GLOW -> 2
            EFFECT_GUMMY -> 4
            EFFECT_PRISM -> 5
            else -> 1
        }
    }
}
