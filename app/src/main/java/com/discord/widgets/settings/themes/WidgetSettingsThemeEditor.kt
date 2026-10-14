package com.discord.widgets.settings.themes

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.databinding.WidgetSettingsThemeEditorBinding
import com.discord.databinding.WidgetSettingsThemeEditorColorItemBinding
import com.discord.databinding.WidgetSettingsThemeEditorNavItemBinding
import com.discord.models.domain.ModelUserSettings
import com.discord.utilities.theme.ThemeColorParser
import com.discord.utilities.theme.ThemeDefinition
import com.discord.utilities.theme.ThemeResolver
import com.discord.utilities.theme.ThemeRuntime
import com.discord.views.RadioManager
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.profile.ProfileColorPickerDialog

/**
 * The theme editor's hub: a live preview, the theme's details and base, the quick colors
 * Aliucord's `simple_colors` offers, and one page per kind of change.
 *
 * Every change is saved as it is made. **Apply theme** turns the theme on (on top of any others
 * that are on) and recreates the screen, so the edit shows across the whole app.
 */
class WidgetSettingsThemeEditor : ThemeEditorPage(R.layout.widget_settings_theme_editor, R.string.microcord_theme_editor_title) {

    private var _binding: WidgetSettingsThemeEditorBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val preview = ThemePreviewDrawable()
    private lateinit var bases: Map<String, com.discord.views.CheckedSetting>
    private lateinit var baseRadios: RadioManager

    override fun onThemeLoaded(view: View) {
        _binding = WidgetSettingsThemeEditorBinding.bind(view)
        setActionBarOptionsMenu(R.menu.menu_settings_theme_editor, onSelected = { item ->
            if (item.itemId == R.id.menu_theme_editor_reset) confirmReset()
        })
        binding.themeEditorPreview.setImageDrawable(preview)
        binding.themeEditorName.setText(theme.manifest.name)
        binding.themeEditorAuthor.setText(theme.manifest.author)
        binding.themeEditorName.doAfterTextChanged { text ->
            val name = text?.toString()?.trim().orEmpty()
            if (name.isNotEmpty() && name != theme.manifest.name) save(theme.copy(manifest = theme.manifest.copy(name = name)))
        }
        binding.themeEditorAuthor.doAfterTextChanged { text ->
            val author = text?.toString()?.trim().orEmpty().ifEmpty { ThemeDefinition.DEFAULT_AUTHOR }
            if (author != theme.manifest.author) save(theme.copy(manifest = theme.manifest.copy(author = author)))
        }
        bases = mapOf(
            ModelUserSettings.THEME_DARK to binding.themeEditorBaseDark,
            ModelUserSettings.THEME_DARKER to binding.themeEditorBaseDarker,
            ModelUserSettings.THEME_MIDNIGHT to binding.themeEditorBaseOnyx,
            ModelUserSettings.THEME_LIGHT to binding.themeEditorBaseLight,
        )
        baseRadios = RadioManager(bases.values.toList())
        bases.forEach { (base, radio) ->
            radio.setContainerClickListener { if (base != theme.base) save(theme.copy(base = base)) }
        }
        binding.themeEditorApply.isEnabled = ThemeRuntime.isSupported
        binding.themeEditorApply.setOnClickListener { confirmApply() }
        childFragmentManager.setFragmentResultListener(ProfileColorPickerDialog.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            val key = result.getString(ProfileColorPickerDialog.RESULT_TARGET) ?: return@setFragmentResultListener
            val color = result.getInt(ProfileColorPickerDialog.RESULT_COLOR).takeIf { result.getBoolean(ProfileColorPickerDialog.RESULT_HAS_COLOR) }
            setQuickColor(key, color)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun render() {
        val binding = _binding ?: return
        val context = requireContext()
        preview.colors = ThemePreviewDrawable.colorsOf(context, theme, theme.base)
        baseRadios.setChecked(bases.getValue(theme.base))
        binding.themeEditorQuickColors.removeAllViews()
        QUICK_COLORS.forEach { (key, label) -> addQuickColor(binding.themeEditorQuickColors, key, label) }
        binding.themeEditorPages.removeAllViews()
        val pages = binding.themeEditorPages
        addPage(pages, R.string.microcord_theme_colors, count(theme.colors.size + theme.attrs.size), WidgetSettingsThemeColors::class.java)
        addPage(pages, R.string.microcord_theme_icons, count(theme.drawableTints.size), WidgetSettingsThemeIcons::class.java)
        addPage(
            pages,
            R.string.microcord_theme_background,
            theme.background?.let { describeFile(context, it.url) } ?: getString(R.string.microcord_theme_background_none),
            WidgetSettingsThemeBackground::class.java,
        )
        addPage(pages, R.string.microcord_theme_fonts, count(theme.fonts.size), WidgetSettingsThemeFiles::class.java, WidgetSettingsThemeFiles.KIND_FONTS)
        addPage(pages, R.string.microcord_theme_sounds, count(theme.raws.size), WidgetSettingsThemeFiles::class.java, WidgetSettingsThemeFiles.KIND_SOUNDS)
    }

    private fun count(changed: Int) = if (changed == 0) getString(R.string.microcord_theme_file_default) else getString(R.string.microcord_theme_changes, changed)

    private fun addQuickColor(parent: LinearLayout, key: String, @StringRes label: Int) {
        val row = WidgetSettingsThemeEditorColorItemBinding.inflate(layoutInflater, parent, true)
        val color = theme.simpleColors[key]
        val system = theme.systemColors["${ThemeDefinition.SECTION_SIMPLE_COLORS}.$key"]
        ThemeColorRows.bind(row, getString(label), color, detail = system ?: color?.let(ThemeColorParser::toHex) ?: getString(R.string.microcord_theme_not_set))
        row.themeEditorColorReset.isVisible = color != null || system != null
        row.themeEditorColorReset.setOnClickListener { setQuickColor(key, null) }
        row.root.setOnClickListener {
            ProfileColorPickerDialog.show(childFragmentManager, initialColor = color ?: DEFAULT_PICK, target = key, title = getString(label), allowAlpha = true)
        }
    }

    private fun addPage(parent: LinearLayout, @StringRes label: Int, detail: String, page: Class<out Fragment>, kind: String? = null) {
        val row = WidgetSettingsThemeEditorNavItemBinding.inflate(layoutInflater, parent, true)
        row.themeEditorNavLabel.setText(label)
        row.themeEditorNavDetail.text = detail
        row.root.setOnClickListener {
            val extras = android.content.Intent().apply { kind?.let { putExtra(WidgetSettingsThemeFiles.ARG_KIND, it) } }
            launch(requireContext(), page, themeId, extras)
        }
    }

    private fun setQuickColor(key: String, color: Int?) {
        val simple = theme.simpleColors.toMutableMap()
        if (color == null) simple.remove(key) else simple[key] = color
        save(theme.copy(simpleColors = simple, systemColors = theme.systemColors - "${ThemeDefinition.SECTION_SIMPLE_COLORS}.$key"))
    }

    private fun confirmReset() {
        WidgetNoticeDialog.show(
            parentFragmentManager,
            headerText = getString(R.string.microcord_theme_reset_everything),
            bodyText = getString(R.string.microcord_theme_reset_everything_confirm),
            okText = getString(R.string.reset),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { _: View ->
                save(ThemeDefinition(manifest = theme.manifest, base = theme.base, extras = theme.extras))
            }),
        )
    }

    /** Text below 3:1 against the main background is hard to read; ask before applying it. */
    private fun confirmApply() {
        val colors = ThemePreviewDrawable.colorsOf(requireContext(), theme, theme.base)
        if (contrast(colors[ThemePreviewDrawable.TEXT], colors[ThemePreviewDrawable.CHAT]) >= MIN_CONTRAST) {
            apply()
            return
        }
        WidgetNoticeDialog.show(
            parentFragmentManager,
            headerText = getString(R.string.microcord_theme_apply),
            bodyText = getString(R.string.microcord_theme_low_contrast),
            okText = getString(R.string.microcord_theme_apply),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { _: View -> apply() }),
        )
    }

    private fun apply() {
        val enabled = store.activeIds
        ThemeActions.enable(this, if (themeId in enabled) enabled else enabled + themeId)
    }

    companion object {
        private const val MIN_CONTRAST = 3.0
        private const val DEFAULT_PICK = 0xFF5865F2.toInt()

        /** Aliucord's `simple_colors`, the quickest way to restyle the app. */
        private val QUICK_COLORS = listOf(
            "accent" to R.string.microcord_theme_simple_accent,
            "background" to R.string.microcord_theme_simple_background,
            "background_secondary" to R.string.microcord_theme_simple_background_secondary,
            "mention_highlight" to R.string.microcord_theme_simple_mention_highlight,
            "active_channel" to R.string.microcord_theme_simple_active_channel,
            "input_background" to R.string.microcord_theme_simple_input_background,
            "blocked_bg" to R.string.microcord_theme_simple_blocked_bg,
        )

        fun launch(context: Context, themeId: String) = launch(context, WidgetSettingsThemeEditor::class.java, themeId)

        /** WCAG contrast, with translucent colors flattened the way they are drawn: text over background over black. */
        private fun contrast(foreground: Int, background: Int): Double {
            val opaque = ColorUtils.compositeColors(background, Color.BLACK)
            return ColorUtils.calculateContrast(ColorUtils.compositeColors(foreground, opaque), opaque)
        }
    }
}
