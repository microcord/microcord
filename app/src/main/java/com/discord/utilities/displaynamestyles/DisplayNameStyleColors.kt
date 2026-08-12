package com.discord.utilities.displaynamestyles

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.discord.api.user.DisplayNameStyles
import kotlin.math.max
import kotlin.math.min

/**
 * The colours an effect actually paints with, derived from the one or more the user picked.
 *
 * Two transformations happen before a colour reaches the screen, both of them the reference's:
 *
 * 1. **Contrast correction** — a colour is walked towards or away from the background until it
 *    clears the effect's minimum contrast ratio, so a dark purple name stays legible on the dark
 *    theme without the user having to pick a lighter one.
 * 2. **Variant derivation** — Toon, Neon and Pop are drawn from lightened and darkened relatives of
 *    the chosen colour rather than from the colour alone.
 */
object DisplayNameStyleColors {

    /** Lighter and darker relatives of one picked colour. */
    data class Variants(
        @ColorInt val main: Int,
        @ColorInt val light1: Int,
        @ColorInt val light2: Int,
        @ColorInt val dark1: Int,
        @ColorInt val dark2: Int,
        @ColorInt val toonStroke: Int,
        @ColorInt val neonStroke: Int,
    )

    fun variantsOf(@ColorInt main: Int): Variants {
        val hsl = hslOf(main)
        val lightness = hsl[2]
        return Variants(
            main = main,
            light1 = withHsl(hsl, lightness = min(1f, lightness * 1.2f)),
            light2 = withHsl(hsl, lightness = min(1f, lightness * 1.6f)),
            dark1 = withHsl(hsl, lightness = max(0f, lightness * 0.6f)),
            dark2 = withHsl(hsl, lightness = max(0f, lightness * 0.2f)),
            toonStroke = withHsl(hsl, lightness = max(0.12f, lightness * 0.4f)),
            neonStroke = withHsl(
                hsl,
                saturation = min(1f, hsl[1] * 1.2f),
                lightness = min(0.6f, lightness + 0.1f),
            ),
        )
    }

    /**
     * [colors] pulled far enough from [background] to clear the effect's contrast floor.
     *
     * Toon is measured against its own stroke rather than the surface behind it: the stroke is what
     * the glyph actually sits on.
     */
    fun accessible(
        colors: List<Int>,
        effectId: Int,
        @ColorInt background: Int,
    ): List<Int> {
        val against = if (effectId == DisplayNameStyles.EFFECT_TOON) TOON_BACKDROP else background
        val ratio = minContrastRatio(effectId)
        return colors.map { accessibleForeground(it, against, ratio) }
    }

    private fun minContrastRatio(effectId: Int): Double = when (effectId) {
        DisplayNameStyles.EFFECT_GRADIENT,
        DisplayNameStyles.EFFECT_GLOW,
        DisplayNameStyles.EFFECT_PRISM,
        -> 2.5
        else -> 3.0
    }

    /**
     * [foreground] stepped in lightness until it reads against [background].
     *
     * Which way to step is decided once, from whichever side of the background the colour is
     * already on, so a light colour on a dark surface keeps getting lighter rather than crossing
     * over. Ten steps is the reference's cap; a colour that still fails is returned as-is, which is
     * better than one driven all the way to black or white.
     */
    @ColorInt
    private fun accessibleForeground(
        @ColorInt foreground: Int,
        @ColorInt background: Int,
        ratio: Double,
    ): Int {
        if (ColorUtils.calculateContrast(opaque(foreground), opaque(background)) >= ratio) {
            return foreground
        }
        val hsl = hslOf(foreground)
        val lighten = ColorUtils.calculateLuminance(opaque(foreground)) >=
            ColorUtils.calculateLuminance(opaque(background))
        var lightness = hsl[2]
        repeat(MAX_CONTRAST_STEPS) {
            lightness = (lightness + if (lighten) CONTRAST_STEP else -CONTRAST_STEP).coerceIn(0f, 1f)
            val candidate = withHsl(hsl, lightness = lightness)
            if (ColorUtils.calculateContrast(opaque(candidate), opaque(background)) >= ratio) {
                return candidate
            }
        }
        return foreground
    }

    /**
     * The four stripes a single picked hue expands into for Gummy.
     *
     * Gummy is defined by four colours but the picker only offers one, so the rest are generated
     * around it at fixed hue offsets — which is what gives every Gummy name the same banding
     * regardless of the hue chosen.
     */
    fun gummyColorsFrom(@ColorInt source: Int): List<Int> {
        val hue = hslOf(source)[0]
        return GUMMY_STRIPES.map { (hueShift, saturation, lightness) ->
            ColorUtils.HSLToColor(
                floatArrayOf(((hue + hueShift) % 360f + 360f) % 360f, saturation, lightness),
            )
        }
    }

    /** The hue a Gummy set was generated from, for re-opening the picker on the right colour. */
    @ColorInt
    fun gummySourceColor(colors: List<Int>): Int {
        val middle = colors.getOrNull(1) ?: colors.firstOrNull() ?: return 0
        val hue = ((hslOf(middle)[0] - GUMMY_STRIPES[1].first) % 360f + 360f) % 360f
        return ColorUtils.HSLToColor(floatArrayOf(hue, GUMMY_HUE_SATURATION, GUMMY_HUE_LIGHTNESS))
    }

    /** Wire colours carry no alpha; anything drawn has to be fully opaque. */
    @ColorInt
    fun opaque(@ColorInt color: Int): Int = color or ALPHA_MASK

    private fun hslOf(@ColorInt color: Int): FloatArray =
        FloatArray(3).also { ColorUtils.colorToHSL(opaque(color), it) }

    @ColorInt
    private fun withHsl(
        hsl: FloatArray,
        saturation: Float = hsl[1],
        lightness: Float = hsl[2],
    ): Int = ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, lightness))

    /** Roughly the dark grey the reference measures Toon against. */
    private const val TOON_BACKDROP = 0xFF333333.toInt()

    private const val ALPHA_MASK = 0xFF000000.toInt()
    private const val CONTRAST_STEP = 0.05f
    private const val MAX_CONTRAST_STEPS = 10

    private const val GUMMY_HUE_SATURATION = 0.78f
    private const val GUMMY_HUE_LIGHTNESS = 0.72f

    /** Hue offset, saturation and lightness of each Gummy stripe. */
    private val GUMMY_STRIPES = listOf(
        Triple(-18f, 0.54f, 0.72f),
        Triple(-5f, 0.66f, 0.60f),
        Triple(9f, 0.56f, 0.68f),
        Triple(22f, 0.60f, 0.63f),
    )
}
