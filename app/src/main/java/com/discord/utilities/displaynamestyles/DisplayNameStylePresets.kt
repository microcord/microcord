package com.discord.utilities.displaynamestyles

import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.user.DisplayNameStyles

/** The colours and names the picker offers, and the order it offers them in. */
object DisplayNameStylePresets {

    /** Effects in picker order; Glow is absent because it draws as Solid. */
    val EFFECT_ORDER: List<Int> = listOf(
        DisplayNameStyles.EFFECT_SOLID,
        DisplayNameStyles.EFFECT_GRADIENT,
        DisplayNameStyles.EFFECT_NEON,
        DisplayNameStyles.EFFECT_TOON,
        DisplayNameStyles.EFFECT_POP,
        DisplayNameStyles.EFFECT_GUMMY,
        DisplayNameStyles.EFFECT_PRISM,
    )

    @StringRes
    fun effectName(effectId: Int): Int = when (effectId) {
        DisplayNameStyles.EFFECT_GRADIENT -> R.string.display_name_effect_gradient
        DisplayNameStyles.EFFECT_NEON -> R.string.display_name_effect_neon
        DisplayNameStyles.EFFECT_TOON -> R.string.display_name_effect_toon
        DisplayNameStyles.EFFECT_POP -> R.string.display_name_effect_pop
        DisplayNameStyles.EFFECT_GUMMY -> R.string.display_name_effect_gummy
        DisplayNameStyles.EFFECT_PRISM -> R.string.display_name_effect_prism
        else -> R.string.display_name_effect_solid
    }

    @StringRes
    fun fontName(fontId: Int): Int = when (fontId) {
        DisplayNameStyles.FONT_ZILLA_SLAB -> R.string.display_name_font_tempo
        DisplayNameStyles.FONT_CHERRY_BOMB -> R.string.display_name_font_sakura
        DisplayNameStyles.FONT_CHICLE -> R.string.display_name_font_jellybean
        DisplayNameStyles.FONT_MUSEO_MODERNO -> R.string.display_name_font_modern
        DisplayNameStyles.FONT_NEO_CASTEL -> R.string.display_name_font_medieval
        DisplayNameStyles.FONT_PIXELIFY -> R.string.display_name_font_8bit
        DisplayNameStyles.FONT_SINISTRE -> R.string.display_name_font_vampyre
        DisplayNameStyles.FONT_PLAYPEN_SANS -> R.string.display_name_font_monkeybars
        DisplayNameStyles.FONT_ORBITRON -> R.string.display_name_font_mainframe
        DisplayNameStyles.FONT_NEW_ROCKER -> R.string.display_name_font_headbang
        DisplayNameStyles.FONT_KALAM -> R.string.display_name_font_journal
        else -> R.string.display_name_font_default
    }

    /** The colour sets an effect offers, each already the length that effect reads. */
    fun presetsFor(effectId: Int): List<List<Int>> = when (effectId) {
        DisplayNameStyles.EFFECT_GRADIENT, DisplayNameStyles.EFFECT_GLOW -> GRADIENT
        DisplayNameStyles.EFFECT_GUMMY -> GUMMY
        DisplayNameStyles.EFFECT_PRISM -> PRISM
        else -> SOLID.map(::listOf)
    }

    /**
     * [colors] adjusted to what [effectId] reads.
     *
     * Switching effect keeps the colour the user already chose where it still fits, and falls back
     * to the effect's first preset rather than to an empty selection, which would render unstyled.
     */
    fun adaptColors(colors: List<Int>, effectId: Int): List<Int> {
        val wanted = DisplayNameStyles.colorCountFor(effectId)
        if (colors.size == wanted) return colors
        val first = colors.firstOrNull() ?: return presetsFor(effectId).first()
        return when (effectId) {
            DisplayNameStyles.EFFECT_GUMMY -> DisplayNameStyleColors.gummyColorsFrom(first)
            else -> presetsFor(effectId).firstOrNull() ?: listOf(first)
        }
    }

    private val SOLID = listOf(
        0x18daad, 0x24e36d, 0x1c98eb, 0xc32aff, 0xfc1965, 0xc6a615,
        0x0a9d7b, 0x0fad4b, 0x0b69a7, 0xa913e5, 0xda0149, 0xf93722,
    )

    private val GRADIENT = listOf(
        listOf(0x2aaea6, 0xffc490),
        listOf(0x26b164, 0x90eaff),
        listOf(0xe45eff, 0x267de0),
        listOf(0x903cda, 0x2cda8e),
        listOf(0xefb4aa, 0xe46ce2),
        listOf(0xdf4232, 0xbd8fff),
        listOf(0xf5983c, 0xe55ae7),
        listOf(0xe4541e, 0x65d7e4),
    )

    private val GUMMY = listOf(
        listOf(0xe9a9d5, 0xa9ddf0, 0xb9a7ee, 0xb9eacb),
        listOf(0xff6fc2, 0xf54fa8, 0xff9bd6, 0xd96ab7),
        listOf(0xffb57a, 0xff936f, 0xff7c78, 0xf06b8a),
        listOf(0xc4ed65, 0x98de60, 0x6ccd63, 0x45bc66),
        listOf(0x7cb8ff, 0x8ee0d0, 0x9d94f5, 0x66cff0),
        listOf(0xd77bff, 0xb56bff, 0xe45ce5, 0x8b6cff),
    )

    private val PRISM = listOf(
        listOf(0xfe9242, 0x35ed7e, 0xffe047, 0xff4cd2, 0x5865f2),
        listOf(0xff3d3d, 0xff8a2a, 0xffd447, 0xff4fa3, 0x7b61ff),
        listOf(0x7a0906, 0xc81e12, 0xf45a18, 0xff9500, 0xffd447),
        listOf(0x7ee7d8, 0x7ccbff, 0x7f8cff, 0xb56dff, 0xf38ad8),
        listOf(0x35d65f, 0x16d8b2, 0x20c5f5, 0x2b7cff, 0x6b4dff),
        listOf(0xa7e8a1, 0xd9f08c, 0xffe08a, 0xffc4a8, 0xf3b6d9),
    )
}
