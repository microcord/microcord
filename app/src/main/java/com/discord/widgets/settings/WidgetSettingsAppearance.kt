package com.discord.widgets.settings

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.appViewModels
import com.discord.databinding.WidgetSettingsAppearanceBinding
import com.discord.models.domain.ModelUserSettings
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.ui
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.discord.views.RadioManager
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.functions.Action1

/**
 * Theme, theme sync and chat font size, with a live preview of a chat message at the chosen size.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsAppearance`. State and the two easter eggs
 * live in [SettingsAppearanceViewModel]; this class renders them.
 *
 * The font-scale slider is offset: `SeekBar` progress `0..70` maps to `80%..150%`, and the special
 * value `-1` means "follow the device setting", which is what the reset link restores.
 */
class WidgetSettingsAppearance : AppFragment(R.layout.widget_settings_appearance) {

    private val viewModel: SettingsAppearanceViewModel by appViewModels {
        SettingsAppearanceViewModel()
    }

    private var _binding: WidgetSettingsAppearanceBinding? = null
    private val binding get() = requireNotNull(_binding)
    private lateinit var themeRadioManager: RadioManager

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsAppearanceBinding.bind(view)
        setActionBarTitle(R.string.appearance)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)

        themeRadioManager = RadioManager(
            listOf(
                binding.settingsAppearanceThemeLightRadio,
                binding.settingsAppearanceThemeDarkRadio,
                binding.settingsAppearanceThemeDarkerRadio,
                binding.settingsAppearanceThemeOnyxRadio,
                binding.settingsAppearanceThemeMaterialYouRadio,
            ),
        )

        binding.settingsAppearanceFontScalingSeekbar.max = SettingsAppearanceViewModel.MAX_FONT_SCALING -
            SettingsAppearanceViewModel.MIN_FONT_SCALING
        binding.settingsAppearanceFontScalingSeekbar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val fontScale = progress + SettingsAppearanceViewModel.MIN_FONT_SCALING
                    viewModel.onFontScaleChanged(fontScale)
                    binding.settingsAppearanceFontScalePlatform.text =
                        fontScaleString(fontScale, useSystemFontScale = false)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
        binding.settingsAppearanceFontScalingReset.setOnClickListener { viewModel.resetFontScale() }

        listOf(
            R.id.settings_appearance_theme_header,
            R.id.settings_appearance_sync_header,
            R.id.settings_appearance_font_scale_header,
        ).forEach { id -> ViewCompat.setAccessibilityHeading(view.findViewById(id), true) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { configureUI(it) }
        viewModel.observeEvents().ui(this).subscribe { handleEvent(it) }
    }

    private fun configureUI(viewState: SettingsAppearanceViewModel.ViewState) {
        if (viewState !is SettingsAppearanceViewModel.ViewState.Loaded) return

        themeRadioManager.setChecked(
            when (viewState.currentTheme) {
                ModelUserSettings.THEME_LIGHT -> binding.settingsAppearanceThemeLightRadio
                ModelUserSettings.THEME_MATERIAL_YOU -> binding.settingsAppearanceThemeMaterialYouRadio
                ModelUserSettings.THEME_DARKER -> binding.settingsAppearanceThemeDarkerRadio
                ModelUserSettings.THEME_MIDNIGHT -> binding.settingsAppearanceThemeOnyxRadio
                else -> binding.settingsAppearanceThemeDarkRadio
            },
        )
        binding.settingsAppearanceThemeLightRadio.setContainerClickListener { viewModel.onLightThemeTapped() }
        binding.settingsAppearanceThemeDarkRadio.setContainerClickListener {
            viewModel.onDarkThemeTapped()
        }
        binding.settingsAppearanceThemeDarkerRadio.setContainerClickListener {
            viewModel.onDarkerThemeTapped()
        }
        binding.settingsAppearanceThemeOnyxRadio.setContainerClickListener {
            viewModel.onOnyxThemeTapped()
        }
        binding.settingsAppearanceThemeMaterialYouRadio.setContainerClickListener {
            viewModel.onMaterialYouThemeTapped()
        }
        binding.settingsAppearanceThemeSystemSwitch.setOnCheckedListener(null)
        binding.settingsAppearanceThemeSystemSwitch.isChecked = viewState.followSystem
        binding.settingsAppearanceThemeSystemSwitch
            .setOnCheckedListener(Action1 { viewModel.onFollowSystemToggled(it) })

        binding.settingsAppearanceSyncSwitch.setOnCheckedListener(null)
        binding.settingsAppearanceSyncSwitch.isChecked = viewState.isThemeSyncEnabled
        binding.settingsAppearanceSyncSwitch.setOnCheckedListener(Action1 { viewModel.setThemeSyncEnabled(it) })
        binding.settingsAppearanceSyncProfileThemesSwitch.setOnCheckedListener(null)
        binding.settingsAppearanceSyncProfileThemesSwitch.isChecked = viewState.syncProfileThemes
        binding.settingsAppearanceSyncProfileThemesSwitch
            .setOnCheckedListener(Action1 { viewModel.setSyncProfileThemes(it) })

        configureFontScalingUI(viewState.fontScale)
        setupPreviewMessage(viewState)
    }

    private fun configureFontScalingUI(fontScale: Int) {
        val useSystemFontScale = fontScale == SettingsAppearanceViewModel.USE_SYSTEM_FONT_SCALE
        val effectiveScale = if (useSystemFontScale) {
            SettingsAppearanceViewModel.systemFontScalePercent(requireContext())
        } else {
            fontScale
        }
        binding.settingsAppearanceFontScalePlatform.text = fontScaleString(effectiveScale, useSystemFontScale)
        binding.settingsAppearanceFontScalingSeekbar.progress =
            effectiveScale - SettingsAppearanceViewModel.MIN_FONT_SCALING
    }

    private fun fontScaleString(fontScale: Int, useSystemFontScale: Boolean): String {
        val source = getString(
            if (useSystemFontScale) {
                R.string.accessibility_font_scaling_use_os
            } else {
                R.string.accessibility_font_scaling_use_app
            },
        )
        return "$fontScale% ($source)"
    }

    private fun setupPreviewMessage(viewState: SettingsAppearanceViewModel.ViewState.Loaded) {
        val preview = binding.chatItem
        IconUtils.setIcon(preview.chatListAdapterItemTextAvatar, viewState.meUser, R.dimen.avatar_size_standard)
        preview.chatListAdapterItemTextTag.visibility = View.GONE
        preview.chatListAdapterItemTextName.text = viewState.meUser.username
        preview.chatListAdapterItemTextTimestamp.text = TimeUtils.toReadableTimeString(
            requireContext(),
            ClockFactory.get().currentTimeMillis(),
        )
        preview.chatListAdapterItemText.text = getString(R.string.user_settings_appearance_preview_message_1)
    }

    private fun handleEvent(event: SettingsAppearanceViewModel.Event) = when (event) {
        is SettingsAppearanceViewModel.Event.ShowHolyLight -> showHolyLight()

        is SettingsAppearanceViewModel.Event.ThemeUpdated ->
            toast(getString(event.messageResId))
    }

    private fun toast(message: CharSequence) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun showHolyLight() {
        toast(getString(R.string.theme_holy_light_reveal))
        setTorchMode(true)
        binding.settingsAppearanceHolyLight.visibility = View.VISIBLE
        Observable.timer(HOLY_LIGHT_DURATION_SECONDS, TimeUnit.SECONDS)
            .ui(this)
            .subscribe {
                _binding?.settingsAppearanceHolyLight?.visibility = View.GONE
                setTorchMode(false)
            }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Best effort: a device with no camera, or one whose torch is in use, simply does not flash. */
    private fun setTorchMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cameraManager = context?.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        runCatching { cameraManager.cameraIdList.firstOrNull() }
            .onFailure { AppLog.w("Unable to turn on flashlight", it) }
            .getOrNull()
            ?.let { cameraId ->
                runCatching { cameraManager.setTorchMode(cameraId, enabled) }
                    .onFailure { AppLog.w("Unable to turn on flashlight", it) }
            }
    }

    companion object {
        private const val HOLY_LIGHT_DURATION_SECONDS = 3L

        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsAppearance::class.java)
        }
    }
}
