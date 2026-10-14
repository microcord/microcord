package com.discord.widgets.settings.themes

import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.discord.R
import com.discord.databinding.WidgetSettingsThemeListPageBinding
import com.discord.utilities.rx.ui
import com.discord.utilities.theme.ThemeColorParser
import com.discord.utilities.theme.ThemeDefinition
import com.discord.widgets.settings.profile.ProfileColorPickerDialog
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Aliucord's `drawable_tints`: recolors individual icons, which most theme colors cannot reach
 * because the icons hardcode their colors.
 *
 * Lists the tinted icons; searching finds any of the app's drawables to tint.
 */
class WidgetSettingsThemeIcons : ThemeEditorPage(R.layout.widget_settings_theme_list_page, R.string.microcord_theme_icons) {

    private var _binding: WidgetSettingsThemeListPageBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val adapter = ThemeEditorListAdapter()
    private var names: List<String> = emptyList()
    private var query = ""

    override fun onThemeLoaded(view: View) {
        _binding = WidgetSettingsThemeListPageBinding.bind(view)
        binding.themeListPageSearch.setHint(getString(R.string.microcord_theme_icons_search))
        binding.themeListPageHelp.setText(R.string.microcord_theme_icons_help)
        binding.themeListPageList.layoutManager = LinearLayoutManager(requireContext())
        binding.themeListPageList.itemAnimator = null
        binding.themeListPageList.adapter = adapter
        binding.themeListPageSearch.bindTextWatcher(this) { text ->
            query = text
            render()
        }
        childFragmentManager.setFragmentResultListener(ProfileColorPickerDialog.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            val name = result.getString(ProfileColorPickerDialog.RESULT_TARGET) ?: return@setFragmentResultListener
            setTint(name, result.getInt(ProfileColorPickerDialog.RESULT_COLOR).takeIf { result.getBoolean(ProfileColorPickerDialog.RESULT_HAS_COLOR) })
        }
        Observable.fromCallable { ThemeTokens.drawableNames() }
            .subscribeOn(Schedulers.computation())
            .ui(this)
            .subscribe { loaded ->
                names = loaded
                render()
            }
    }

    override fun onDestroyView() {
        _binding?.themeListPageList?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun render() {
        val binding = _binding ?: return
        val needle = query.trim().lowercase()
        binding.themeListPageHelp.isVisible = needle.isEmpty() && theme.drawableTints.isEmpty()
        val items = mutableListOf<ThemeEditorListAdapter.Item>()
        if (needle.isEmpty()) {
            if (theme.drawableTints.isNotEmpty()) {
                items += ThemeEditorListAdapter.Item.Header("tinted", getString(R.string.microcord_theme_icons_tinted))
                theme.drawableTints.keys.sorted().forEach { items += iconItem(it) }
            }
        } else {
            names.asSequence().filter { needle in it }.take(MAX_RESULTS).forEach { items += iconItem(it) }
        }
        adapter.submitList(items)
    }

    private fun iconItem(name: String): ThemeEditorListAdapter.Item {
        val tint = theme.drawableTints[name]
        val context = requireContext()
        return ThemeEditorListAdapter.Item.Color(
            id = name,
            label = name,
            color = tint,
            detail = tint?.let(ThemeColorParser::toHex) ?: getString(R.string.microcord_theme_file_default),
            overridden = tint != null,
            icon = {
                val id = context.resources.getIdentifier(name, "drawable", context.packageName)
                if (id == 0) null else runCatching { AppCompatResources.getDrawable(context, id)?.mutate() }.getOrNull()
            },
            onClick = {
                ProfileColorPickerDialog.show(childFragmentManager, initialColor = tint ?: DEFAULT_PICK, target = name, title = name, allowAlpha = true)
            },
            onReset = { setTint(name, null) },
        )
    }

    private fun setTint(name: String, color: Int?) {
        val tints = theme.drawableTints.toMutableMap()
        if (color == null) tints.remove(name) else tints[name] = color
        save(theme.copy(drawableTints = tints, systemColors = theme.systemColors - "${ThemeDefinition.SECTION_DRAWABLE_TINTS}.$name"))
    }

    private companion object {
        const val MAX_RESULTS = 200
        const val DEFAULT_PICK = -1
    }
}
