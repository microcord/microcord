package com.discord.widgets.settings.themes

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsCustomClientThemeBinding
import com.discord.models.domain.ModelUserSettings
import com.discord.utilities.theme.ClientTheme
import com.discord.utilities.theme.ThemeRuntime
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.views.ThemedColor
import com.discord.widgets.servers.RoleColorPickerDialog
import com.discord.widgets.settings.SettingsNavigation

/**
 * Makes a Nitro custom theme, the kind desktop's editor makes: up to [ClientTheme.MAX_COLORS]
 * colors, the gradient's angle, how much of the colors comes through, and the built-in theme it
 * sits on. Opens on the custom theme in use, if any. Applying works like picking a preset.
 */
class WidgetSettingsCustomClientTheme : AppFragment(R.layout.widget_settings_custom_client_theme) {

    private var colors = mutableListOf<Int>()
    private var angle = DEFAULT_ANGLE
    private var baseMix = DEFAULT_BASE_MIX
    private var base = ModelUserSettings.THEME_DARKER

    private var _binding: WidgetSettingsCustomClientThemeBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = savedInstanceState?.getIntArray(STATE_COLORS)
        if (saved != null) {
            colors = saved.toMutableList()
            angle = savedInstanceState.getInt(STATE_ANGLE)
            baseMix = savedInstanceState.getInt(STATE_BASE_MIX)
            base = savedInstanceState.getString(STATE_BASE) ?: base
            return
        }
        val current = ThemeRuntime.store(requireContext()).clientTheme as? ClientTheme.Custom
        colors = current?.colors?.take(ClientTheme.MAX_COLORS)?.toMutableList() ?: DEFAULT_COLORS.toMutableList()
        angle = current?.angle?.toInt() ?: DEFAULT_ANGLE
        baseMix = current?.baseMix ?: DEFAULT_BASE_MIX
        base = current?.base ?: base
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(STATE_COLORS, colors.toIntArray())
        outState.putInt(STATE_ANGLE, angle)
        outState.putInt(STATE_BASE_MIX, baseMix)
        outState.putString(STATE_BASE, base)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsCustomClientThemeBinding.bind(view)
        setActionBarTitle(R.string.manticord_custom_theme_title)
        setActionBarSubtitle(R.string.appearance)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)

        // The picker edits every color at once, a tab each, and answers with all of them.
        setFragmentResultListener(RoleColorPickerDialog.REQUEST_KEY) { _, result ->
            val picked = result.getIntArray(RoleColorPickerDialog.RESULT_COLORS) ?: return@setFragmentResultListener
            colors = picked.map { it or OPAQUE }.toMutableList()
            render()
        }
        binding.customClientThemeAddColor.setOnClickListener {
            if (colors.size < ClientTheme.MAX_COLORS) colors += colors.last()
            render()
        }
        binding.customClientThemeRemoveColor.setOnClickListener {
            if (colors.size > 1) colors.removeAt(colors.lastIndex)
            render()
        }
        binding.customClientThemeAngle.progress = angle
        binding.customClientThemeAngle.setOnSeekBarChangeListener(onChange { angle = it })
        binding.customClientThemeMix.progress = baseMix
        binding.customClientThemeMix.setOnSeekBarChangeListener(onChange { baseMix = it })

        val bases = mapOf(
            ModelUserSettings.THEME_DARKER to binding.customClientThemeBaseDark,
            ModelUserSettings.THEME_MIDNIGHT to binding.customClientThemeBaseOnyx,
            ModelUserSettings.THEME_LIGHT to binding.customClientThemeBaseLight,
        )
        val radios = RadioManager(bases.values.toList())
        radios.setChecked(bases[base] ?: binding.customClientThemeBaseDark)
        bases.forEach { (name, radio: CheckedSetting) ->
            radio.setContainerClickListener {
                base = name
                radios.setChecked(radio)
                render()
            }
        }
        binding.customClientThemeApply.setOnClickListener {
            val theme = ClientTheme.custom(base, colors, emptyList(), angle.toFloat(), baseMix, synced = false) ?: return@setOnClickListener
            if (ThemeActions.applyClientTheme(appActivity, theme)) requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        render()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun onChange(set: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            set(progress)
            render()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun render() {
        val binding = _binding ?: return
        binding.customClientThemeAngleLabel.text = getString(R.string.manticord_custom_theme_angle, angle)
        binding.customClientThemeMixLabel.text = getString(R.string.manticord_custom_theme_mix, baseMix)
        binding.customClientThemeAddColor.isEnabled = colors.size < ClientTheme.MAX_COLORS
        binding.customClientThemeRemoveColor.isEnabled = colors.size > 1
        renderChips(binding.customClientThemeColors)
        val theme = ClientTheme.custom(base, colors, emptyList(), angle.toFloat(), baseMix, synced = false) ?: return
        val preview = binding.customClientThemePreview
        val width = preview.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - 2 * resources.getDimensionPixelSize(R.dimen.manticord_custom_theme_margin))
        val height = resources.getDimensionPixelSize(R.dimen.manticord_custom_theme_preview_height)
        preview.setImageDrawable(
            RoundedBitmapDrawableFactory.create(resources, theme.render(width / 2, height / 2)).apply {
                cornerRadius = resources.getDimension(R.dimen.manticord_custom_theme_corner)
            },
        )
    }

    private fun renderChips(container: LinearLayout) {
        container.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.manticord_custom_theme_chip)
        val ring = ThemedColor.resolve(requireContext(), R.attr.colorBackgroundModifierStrong)
        colors.forEach { color ->
            val chip = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = size / 5; marginEnd = size / 5 }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(resources.getDimensionPixelSize(R.dimen.manticord_nitro_theme_ring), ring)
                }
                contentDescription = getString(R.string.manticord_custom_theme_colors)
                setOnClickListener {
                    RoleColorPickerDialog.showMulti(parentFragmentManager, colors, R.string.manticord_custom_theme_colors)
                }
            }
            container.addView(chip)
        }
    }

    companion object {
        private const val STATE_COLORS = "colors"
        private const val STATE_ANGLE = "angle"
        private const val STATE_BASE_MIX = "base_mix"
        private const val STATE_BASE = "base"
        private const val OPAQUE = 0xFF000000.toInt()

        /** RN's random themes mix 70-80; blurple to fuchsia is a fine first look. */
        private const val DEFAULT_ANGLE = 135
        private const val DEFAULT_BASE_MIX = 75
        private val DEFAULT_COLORS = listOf(0xFF5865F2.toInt(), 0xFFEB459E.toInt())

        fun launch(context: Context) = SettingsNavigation.launch(context, WidgetSettingsCustomClientTheme::class.java)
    }
}
