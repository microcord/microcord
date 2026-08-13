package com.discord.utilities.displaynamestyles

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.annotation.ColorInt
import com.discord.R
import com.discord.api.user.DisplayNameStyles
import com.discord.utilities.color.ColorCompat
import com.discord.views.DisplayNameTextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Draws a name in the font, colours and effect its owner picked.
 *
 * Applied to a whole [TextView] rather than as a span, because every surface that shows a styled
 * name shows *only* the name in that view, and three of the effects reach past what a span can
 * change: Toon, Neon and Pop need an outline or a second copy of the text, which is a property of
 * the paint and the draw rather than of a run of characters. Those are drawn by
 * [DisplayNameTextView]; on a plain `TextView` they degrade to a shadow.
 *
 * [Mode] is the reference's split, not a convenience. A guild member list or a chat author gets the
 * font and nothing else — colouring hundreds of rows costs a measurable amount of frame time, and
 * the reference makes the same trade.
 *
 * Every entry point resets the view first, because these views are recycled and a stale shader
 * outlives the name that asked for it.
 */
object DisplayNameStyleRenderer {

    enum class Mode {
        /** Font only: no colour, no effect. */
        FONT,

        /** Font, colour and effect. */
        FULL,
    }

    /**
     * Paints [styles] onto [view], or restores it to the theme's own styling when [styles] is null.
     *
     * @param defaultColor the colour the view would use unstyled; a name whose style carries no
     *   colours keeps it. It defaults to the colour the view is already wearing, which is right for
     *   a row that has just been told its member's role colour, and wrong for a preview that is
     *   restyled in place — those pass their own, because by the second call the view is wearing
     *   the last style's colour rather than the theme's.
     */
    @JvmStatic
    @JvmOverloads
    fun apply(
        view: TextView,
        styles: DisplayNameStyles?,
        mode: Mode = Mode.FULL,
        @ColorInt defaultColor: Int = view.currentTextColor,
    ) {
        reset(view, defaultColor)
        if (styles == null) return

        DisplayNameStyleFonts.typefaceFor(view.context, styles.fontId)?.let { view.typeface = it }
        if (mode == Mode.FONT) return

        val colors = styles.colors
            .take(DisplayNameStyles.colorCountFor(styles.effectId))
            .map(DisplayNameStyleColors::opaque)
        if (colors.isEmpty()) return

        val background = ColorCompat.getThemedColor(view, R.attr.colorBackgroundPrimary)
        val accessible = DisplayNameStyleColors.accessible(colors, styles.effectId, background)
        val variants = DisplayNameStyleColors.variantsOf(accessible.first())
        val fontSize = view.textSize

        when (styles.effectId) {
            DisplayNameStyles.EFFECT_GRADIENT, DisplayNameStyles.EFFECT_GLOW ->
                view.applyGradient(accessible, GRADIENT_ANGLE_DEGREES, estimatedWidth(view))

            DisplayNameStyles.EFFECT_PRISM -> view.applyGradient(
                // The first colour is repeated as a closing stop so the last distinct one does not
                // land on the clamped edge and get cut off on a long name.
                accessible + accessible.first(),
                PRISM_ANGLE_DEGREES,
                max(estimatedWidth(view), MIN_PRISM_WIDTH_DP * view.resources.displayMetrics.density),
            )

            DisplayNameStyles.EFFECT_TOON -> {
                view.applyGradient(
                    listOf(Color.WHITE, variants.light2, variants.light1, variants.main),
                    TOON_ANGLE_DEGREES,
                    view.lineHeight.toFloat(),
                )
                view.outline(variants.toonStroke, strokeWidth(TOON_STROKE_BASE, fontSize))
            }

            DisplayNameStyles.EFFECT_NEON -> {
                view.setTextColor(Color.WHITE)
                view.outline(variants.neonStroke, strokeWidth(NEON_STROKE_BASE, fontSize))
                view.setShadowLayer(NEON_SHADOW_BASE + fontSize * NEON_SHADOW_SCALE, 0f, 0f, variants.main)
            }

            DisplayNameStyles.EFFECT_POP -> {
                view.setTextColor(Color.WHITE)
                view.outline(variants.dark2, strokeWidth(POP_STROKE_BASE, fontSize))
                (view as? DisplayNameTextView)?.setNameBackLayer(
                    variants.main,
                    POP_OFFSET_BASE + fontSize * POP_OFFSET_SCALE,
                    strokeWidth(POP_STROKE_BASE, fontSize),
                ) ?: view.setShadowLayer(
                    HARD_SHADOW_RADIUS,
                    0f,
                    POP_OFFSET_BASE + fontSize * POP_OFFSET_SCALE,
                    variants.main,
                )
            }

            DisplayNameStyles.EFFECT_GUMMY -> view.applyPerCharacterColors(accessible)

            // SOLID, and anything a newer client adds.
            else -> view.setTextColor(accessible.first())
        }
    }

    /** Whether [fontId] would change how [view] draws, for callers that only offer the font. */
    @JvmStatic
    fun hasFont(styles: DisplayNameStyles?): Boolean =
        styles != null && styles.fontId != DisplayNameStyles.FONT_DEFAULT

    private fun reset(view: TextView, @ColorInt defaultColor: Int) {
        view.paint.shader = null
        view.paint.style = Paint.Style.FILL
        view.paint.strokeWidth = 0f
        view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        (view as? DisplayNameTextView)?.clearNameLayers()
        view.typeface = defaultTypeface(view)
        view.setTextColor(defaultColor)
        (view.text as? Spannable)?.let { text ->
            text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach(text::removeSpan)
        }
    }

    /**
     * The face the view had before any style touched it.
     *
     * Stashed on the view itself the first time it is styled: a recycled row has no other record of
     * what it started as, and reading `typeface` after a style has been applied returns the style's.
     */
    private fun defaultTypeface(view: TextView): Typeface? {
        val stored = view.getTag(R.id.display_name_style_default_typeface)
        if (stored != null) return stored as? Typeface
        view.setTag(R.id.display_name_style_default_typeface, view.typeface ?: Typeface.DEFAULT)
        return view.typeface
    }

    /**
     * The reference sizes its gradients from an estimate rather than from the laid-out view, so the
     * pattern is the same width whether or not the name has been measured yet.
     */
    private fun estimatedWidth(view: TextView): Float =
        max(view.text?.length ?: 0, 1) * view.textSize * AVERAGE_GLYPH_WIDTH_RATIO

    private fun TextView.applyGradient(colors: List<Int>, angleDegrees: Float, length: Float) {
        if (colors.size < 2) {
            setTextColor(colors.firstOrNull() ?: currentTextColor)
            return
        }
        val radians = Math.toRadians(angleDegrees.toDouble())
        paint.shader = LinearGradient(
            0f,
            0f,
            (length * cos(radians)).toFloat(),
            (length * sin(radians)).toFloat(),
            colors.toIntArray(),
            null,
            Shader.TileMode.CLAMP,
        )
        // A shader multiplies with the paint colour, so anything translucent would wash it out.
        setTextColor(Color.BLACK)
    }

    /**
     * An outline around the glyphs.
     *
     * A [DisplayNameTextView] draws it as a real stroke in a pass of its own. Any other `TextView`
     * has one `Paint` and therefore no way to stroke in a colour the fill is not already using, so
     * it falls back to a tight shadow at no offset, which reads as the same edge.
     */
    private fun TextView.outline(@ColorInt color: Int, width: Float) {
        if (this is DisplayNameTextView) setNameStroke(color, width) else setShadowLayer(width, 0f, 0f, color)
    }

    /** Gummy rotates its colours a character at a time, skipping spaces so words stay readable. */
    private fun TextView.applyPerCharacterColors(colors: List<Int>) {
        val source = text ?: return
        val spannable = SpannableString(source)
        var index = 0
        source.forEachIndexed { position, character ->
            if (character.isWhitespace()) return@forEachIndexed
            spannable.setSpan(
                ForegroundColorSpan(colors[index % colors.size]),
                position,
                position + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            index++
        }
        text = spannable
    }

    private fun strokeWidth(base: Float, fontSize: Float) = base + fontSize * STROKE_SCALE

    /** Rough mean glyph width as a fraction of the font size, for sizing a gradient. */
    private const val AVERAGE_GLYPH_WIDTH_RATIO = 0.6f

    private const val GRADIENT_ANGLE_DEGREES = 45f
    private const val PRISM_ANGLE_DEGREES = 0f
    private const val TOON_ANGLE_DEGREES = 90f
    private const val MIN_PRISM_WIDTH_DP = 38f

    private const val STROKE_SCALE = 0.04f
    private const val NEON_STROKE_BASE = 1f
    private const val TOON_STROKE_BASE = 1.6f
    private const val POP_STROKE_BASE = 1.2f

    private const val NEON_SHADOW_BASE = 4f
    private const val NEON_SHADOW_SCALE = 0.12f

    private const val POP_OFFSET_BASE = 1.2f
    private const val POP_OFFSET_SCALE = 0.04f

    /** A blur radius has to be positive, so "no blur" is the smallest value that still draws. */
    private const val HARD_SHADOW_RADIUS = 0.01f
}
