package com.discord.widgets.settings.themes

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.discord.R
import com.discord.databinding.WidgetSettingsThemeEditorColorItemBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels

/** Binds the theme editors' color row: a round swatch with a hairline border, a label and a detail line. */
internal object ThemeColorRows {
    fun bind(row: WidgetSettingsThemeEditorColorItemBinding, label: CharSequence, color: Int?, detail: CharSequence) {
        val context = row.root.context
        val swatch = row.themeEditorColorSwatch.background as? GradientDrawable
            ?: GradientDrawable().apply { shape = GradientDrawable.OVAL }.also { row.themeEditorColorSwatch.background = it }
        swatch.setColor(color ?: Color.TRANSPARENT)
        swatch.setStroke(dpToPixels(1), ColorCompat.getThemedColor(context, R.attr.colorBackgroundModifierAccent))
        row.themeEditorColorLabel.text = label
        row.themeEditorColorDetail.text = detail
    }
}
