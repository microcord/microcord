package com.discord.widgets.settings.profile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.api.user.DisplayNameStyles
import com.discord.app.AppBottomSheet
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.displaynamestyles.DisplayNameStyleColors
import com.discord.utilities.displaynamestyles.DisplayNameStyleFonts
import com.discord.utilities.displaynamestyles.DisplayNameStylePresets
import com.discord.utilities.displaynamestyles.DisplayNameStyleRenderer
import com.discord.views.DisplayNameTextView
import android.widget.GridLayout
import com.google.android.material.button.MaterialButton
import com.discord.widgets.servers.RoleColorPickerDialog

/**
 * Picks one part of a display name style: the font, the effect, or the colours.
 *
 * One sheet rather than five because the differences are all in the tile — a font tile draws the
 * letters `Gg` in that face, an effect tile draws the effect's own name through the real renderer,
 * and a colour tile is the colours themselves. The grid, the selection border and the confirm
 * button are identical, so [Kind] is the only thing that varies.
 *
 * Nothing is written here. The choice goes back to the editor as a fragment result and stays
 * pending there until the whole style is applied.
 */
class WidgetDisplayNameStyleSheet : AppBottomSheet() {

    enum class Kind { FONT, EFFECT, COLOR, GRADIENT, GUMMY }

    private lateinit var kind: Kind
    private var effectId: Int = DisplayNameStyles.EFFECT_SOLID
    private var selectedFontId: Int = DisplayNameStyles.FONT_DEFAULT
    private var selectedColors: List<Int> = emptyList()

    private lateinit var grid: GridLayout

    override fun getContentViewResId(): Int = R.layout.widget_display_name_style_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        kind = Kind.valueOf(argumentsOrDefault.getString(ARG_KIND, Kind.FONT.name))
        effectId = argumentsOrDefault.getInt(ARG_EFFECT_ID, DisplayNameStyles.EFFECT_SOLID)
        selectedFontId = argumentsOrDefault.getInt(ARG_FONT_ID, DisplayNameStyles.FONT_DEFAULT)
        selectedColors = argumentsOrDefault.getIntArray(ARG_COLORS)?.toList().orEmpty()

        view.findViewById<TextView>(R.id.display_name_style_sheet_title).setText(titleRes())
        grid = view.findViewById(R.id.display_name_style_sheet_grid)

        view.findViewById<MaterialButton>(R.id.display_name_style_sheet_default).apply {
            // Only a solid colour has a "no colour" state to fall back to; every other effect is
            // defined by its colours.
            isVisible = kind == Kind.COLOR && effectId == DisplayNameStyles.EFFECT_SOLID
            setOnClickListener { commitColors(emptyList()) }
        }
        view.findViewById<MaterialButton>(R.id.display_name_style_sheet_custom).apply {
            isVisible = kind == Kind.COLOR || kind == Kind.GRADIENT || kind == Kind.GUMMY
            setText(R.string.custom_color)
            setOnClickListener { pickCustomColor() }
        }
        view.findViewById<TextView>(R.id.display_name_style_sheet_disclaimer).isVisible =
            kind == Kind.FONT

        // The custom picker reports through its own result key; the sheet stays up behind it.
        setFragmentResultListener(RoleColorPickerDialog.REQUEST_KEY) { _, bundle ->
            val picked = bundle.getIntArray(RoleColorPickerDialog.RESULT_COLORS)
                ?.map { it and RGB_MASK }
                ?: listOf(bundle.getInt(RoleColorPickerDialog.RESULT_COLOR) and RGB_MASK)
            commitColors(
                // Gummy is defined by four stripes generated from one hue, so it takes the picked
                // colour and expands it; the rest already have every stop they need.
                if (kind == Kind.GUMMY) {
                    DisplayNameStyleColors.gummyColorsFrom(picked.first()).map { it and RGB_MASK }
                } else {
                    picked
                },
            )
        }
        buildGrid()
    }

    /**
     * Columns per row.
     *
     * Every tile is stretched to an equal share of the width rather than given a fixed size, so a
     * row always reaches both edges instead of leaving whatever the fixed widths did not divide
     * into.
     */
    private fun columnCount(): Int = when (kind) {
        Kind.FONT -> 4
        Kind.EFFECT -> 3
        Kind.COLOR -> 6
        else -> 4
    }

    private fun titleRes(): Int = when (kind) {
        Kind.FONT -> R.string.display_name_styles_choose_font
        Kind.EFFECT -> R.string.display_name_styles_choose_effect
        else -> R.string.display_name_styles_choose_color
    }

    private fun buildGrid() {
        grid.removeAllViews()
        grid.columnCount = columnCount()
        when (kind) {
            Kind.FONT -> DisplayNameStyleFonts.PICKER_ORDER.forEach(::addFontTile)
            Kind.EFFECT -> DisplayNameStylePresets.EFFECT_ORDER.forEach(::addEffectTile)
            else -> DisplayNameStylePresets.presetsFor(effectId).forEach(::addColorTile)
        }
    }

    /** A fixed square showing `Gg`, so every face is compared at the same size. */
    private fun addFontTile(fontId: Int) {
        val tile = tileFrame(FONT_TILE_DP, fontId == selectedFontId)
        val sample = TextView(requireContext()).apply {
            text = FONT_SAMPLE
            textSize = FONT_SAMPLE_SP
            gravity = Gravity.CENTER
            setTextColor(ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary))
            DisplayNameStyleFonts.typefaceFor(context, fontId)?.let { typeface = it }
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        tile.addView(sample)
        tile.setOnClickListener {
            setFragmentResult(RESULT_KEY, bundleOf(RESULT_FONT_ID to fontId))
            dismiss()
        }
        grid.addView(tile)
    }

    /** The effect's own name, drawn with that effect at its default colours. */
    private fun addEffectTile(id: Int) {
        val tile = tileFrame(EFFECT_TILE_HEIGHT_DP, id == effectId)
        val sample = DisplayNameTextView(requireContext()).apply {
            text = getString(DisplayNameStylePresets.effectName(id))
            gravity = Gravity.CENTER
            setTextColor(ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        tile.addView(sample)
        // After layout: the renderer sizes its gradients from the text, which is not measured yet.
        sample.post {
            DisplayNameStyleRenderer.apply(
                sample,
                DisplayNameStyles(
                    DisplayNameStyles.FONT_DEFAULT,
                    id,
                    DisplayNameStylePresets.presetsFor(id).first(),
                ),
                overrideSettings = true,
            )
        }
        tile.setOnClickListener {
            setFragmentResult(RESULT_KEY, bundleOf(RESULT_EFFECT_ID to id))
            dismiss()
        }
        grid.addView(tile)
    }

    private fun addColorTile(colors: List<Int>) {
        val tile = tileFrame(COLOR_TILE_DP, colors == selectedColors)
        val swatch = View(requireContext()).apply {
            background = swatchOf(colors)
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        }
        tile.addView(swatch)
        tile.setOnClickListener { commitColors(colors) }
        grid.addView(tile)
    }

    /** A bordered cell; the border is what marks the current choice. */
    private fun tileFrame(heightDp: Int, selected: Boolean): FrameLayout =
        FrameLayout(requireContext()).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(heightDp)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(TILE_GAP_DP), dp(TILE_GAP_DP), dp(TILE_GAP_DP), dp(TILE_GAP_DP))
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(TILE_RADIUS_DP).toFloat()
                setColor(ColorCompat.getThemedColor(context, R.attr.colorBackgroundTertiary))
                setStroke(
                    dp(TILE_STROKE_DP),
                    if (selected) {
                        ColorCompat.getThemedColor(context, R.attr.color_brand)
                    } else {
                        Color.TRANSPARENT
                    },
                )
            }
            isClickable = true
        }

    private fun swatchOf(colors: List<Int>): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        colors.map(DisplayNameStyleColors::opaque)
            .let { if (it.size == 1) listOf(it.first(), it.first()) else it }
            .toIntArray(),
    ).apply { cornerRadius = dp(TILE_RADIUS_DP).toFloat() }

    /**
     * Opens the app's own colour dialog, so this matches every other colour choice in settings.
     *
     * A gradient or a prism edits all of its stops in one pass — one tab each — rather than making
     * the user reopen the dialog per stop.
     */
    private fun pickCustomColor() {
        // Gummy has four stripes but only one of them is chosen: the rest are derived from it, so
        // it takes the single picker and re-derives. A gradient or prism really is picked stop by
        // stop, and gets a tab each.
        if (kind != Kind.GUMMY && DisplayNameStyles.colorCountFor(effectId) > 1) {
            RoleColorPickerDialog.showMulti(
                parentFragmentManager,
                DisplayNameStylePresets.adaptColors(selectedColors, effectId),
                R.string.pick_a_color,
            )
            return
        }
        val seed = if (kind == Kind.GUMMY) {
            DisplayNameStyleColors.gummySourceColor(selectedColors)
        } else {
            selectedColors.firstOrNull() ?: 0
        }
        RoleColorPickerDialog.show(parentFragmentManager, seed, R.string.pick_a_color)
    }

    private fun commitColors(colors: List<Int>) {
        setFragmentResult(RESULT_KEY, bundleOf(RESULT_COLORS to colors.toIntArray()))
        dismiss()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val RESULT_KEY = "DISPLAY_NAME_STYLE_PICK"
        const val RESULT_FONT_ID = "FONT_ID"
        const val RESULT_EFFECT_ID = "EFFECT_ID"
        const val RESULT_COLORS = "COLORS"

        private const val ARG_KIND = "ARG_KIND"
        private const val ARG_EFFECT_ID = "ARG_EFFECT_ID"
        private const val ARG_FONT_ID = "ARG_FONT_ID"
        private const val ARG_COLORS = "ARG_COLORS"

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val RGB_MASK = 0xFFFFFF

        private const val FONT_SAMPLE = "Gg"
        private const val FONT_SAMPLE_SP = 24f
        private const val FONT_TILE_DP = 72
        private const val EFFECT_TILE_HEIGHT_DP = 72
        private const val COLOR_TILE_DP = 44
        private const val TILE_GAP_DP = 4
        private const val TILE_RADIUS_DP = 8
        private const val TILE_STROKE_DP = 2

        /** Which colour sheet an effect needs: they differ by how many colours they carry. */
        fun colorKindFor(effectId: Int): Kind = when (effectId) {
            DisplayNameStyles.EFFECT_GUMMY -> Kind.GUMMY
            DisplayNameStyles.EFFECT_GRADIENT,
            DisplayNameStyles.EFFECT_GLOW,
            DisplayNameStyles.EFFECT_PRISM,
            -> Kind.GRADIENT
            else -> Kind.COLOR
        }

        fun show(
            fragmentManager: FragmentManager,
            kind: Kind,
            style: DisplayNameStyles,
        ) {
            WidgetDisplayNameStyleSheet().apply {
                arguments = bundleOf(
                    ARG_KIND to kind.name,
                    ARG_EFFECT_ID to style.effectId,
                    ARG_FONT_ID to style.fontId,
                    ARG_COLORS to style.colors.toIntArray(),
                )
            }.show(fragmentManager, WidgetDisplayNameStyleSheet::class.java.simpleName)
        }
    }
}
