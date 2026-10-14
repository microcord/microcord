package com.discord.widgets.settings.themes

import android.content.Context
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.discord.R
import com.discord.app.AppToast
import com.discord.databinding.WidgetSettingsThemeListPageBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.ui
import com.discord.utilities.theme.ThemeAssets
import com.discord.utilities.theme.ThemeDefinition
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Aliucord's `fonts` and `raws`: replacement font files and sounds, one row per replaceable one.
 *
 * A row picks a file from the device, which is copied into the app; Aliucord themes that link
 * their files keep the links and download them when applied.
 */
class WidgetSettingsThemeFiles : ThemeEditorPage(R.layout.widget_settings_theme_list_page, R.string.microcord_theme_fonts) {

    private var _binding: WidgetSettingsThemeListPageBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val adapter = ThemeEditorListAdapter()
    private val fonts get() = arguments?.getString(ARG_KIND) != KIND_SOUNDS

    /** The row a picked file goes to; survives the picker's round trip through the arguments. */
    private var pickingFor: String?
        get() = arguments?.getString(STATE_PICKING)
        set(value) = requireArguments().putString(STATE_PICKING, value)

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = pickingFor ?: return@registerForActivityResult
        pickingFor = null
        uri ?: return@registerForActivityResult
        val context = requireContext().applicationContext
        val extension = if (fonts) ".ttf" else ".audio"
        Observable.fromCallable { ThemeAssets.import(context, uri, extension) }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe(
                { reference -> set(key, reference) },
                { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
            )
    }

    override fun onThemeLoaded(view: View) {
        _binding = WidgetSettingsThemeListPageBinding.bind(view)
        if (!fonts) setActionBarTitle(R.string.microcord_theme_sounds)
        binding.themeListPageSearch.isVisible = false
        binding.themeListPageList.layoutManager = LinearLayoutManager(requireContext())
        binding.themeListPageList.itemAnimator = null
        binding.themeListPageList.adapter = adapter
    }

    override fun onDestroyView() {
        _binding?.themeListPageList?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun render() {
        if (_binding == null) return
        val context = requireContext()
        val keys = if (fonts) listOf(ThemeDefinition.ALL_FONTS) + ThemeDefinition.FONTS else ThemeDefinition.SOUNDS
        val values = if (fonts) theme.fonts else theme.raws
        val icon = if (fonts) R.drawable.ic_text_wrap_24dp else R.drawable.ic_sound_24dp
        val iconColor = ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal)
        adapter.submitList(keys.map { key ->
            val reference = values[key]
            ThemeEditorListAdapter.Item.Color(
                id = key,
                label = label(context, key),
                color = iconColor,
                detail = describeFile(context, reference),
                overridden = reference != null,
                icon = { AppCompatResources.getDrawable(context, icon) },
                onClick = {
                    pickingFor = key
                    pickFile.launch(if (fonts) arrayOf("font/*", "application/x-font-ttf", "application/octet-stream", "*/*") else arrayOf("audio/*"))
                },
                onReset = { set(key, null) },
            )
        })
    }

    private fun set(key: String, reference: String?) {
        val values = (if (fonts) theme.fonts else theme.raws).toMutableMap()
        if (reference == null) values.remove(key) else values[key] = reference
        save(if (fonts) theme.copy(fonts = values) else theme.copy(raws = values))
    }

    companion object {
        const val ARG_KIND = "kind"
        const val KIND_FONTS = "fonts"
        const val KIND_SOUNDS = "sounds"
        private const val STATE_PICKING = "picking"

        /** `whitney_semibold` → "Whitney semibold"; `*` → "All fonts". */
        private fun label(context: Context, key: String) =
            if (key == ThemeDefinition.ALL_FONTS) context.getString(R.string.microcord_theme_fonts_all) else ThemeTokens.label(key)
    }
}
