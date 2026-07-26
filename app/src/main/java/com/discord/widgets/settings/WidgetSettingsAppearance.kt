package com.discord.widgets.settings

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.appViewModels
import com.discord.models.domain.ModelUserSettings
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.ui
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.discord.views.CheckedSetting
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

    private lateinit var lightRadio: CheckedSetting
    private lateinit var darkRadio: CheckedSetting
    private lateinit var pureEvilSwitch: CheckedSetting
    private lateinit var themeRadioManager: RadioManager
    private lateinit var syncSwitch: CheckedSetting
    private lateinit var fontScalePlatform: TextView
    private lateinit var fontScaleSeekBar: SeekBar
    private lateinit var fontScaleReset: TextView
    private lateinit var holyLight: View
    private lateinit var previewAvatar: ImageView
    private lateinit var previewName: TextView
    private lateinit var previewTag: TextView
    private lateinit var previewTimestamp: TextView
    private lateinit var previewText: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.appearance)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()

        lightRadio = view.findViewById(R.id.settings_appearance_theme_light_radio)
        darkRadio = view.findViewById(R.id.settings_appearance_theme_dark_radio)
        pureEvilSwitch = view.findViewById(R.id.settings_appearance_theme_pure_evil_switch)
        syncSwitch = view.findViewById(R.id.settings_appearance_sync_switch)
        fontScalePlatform = view.findViewById(R.id.settings_appearance_font_scale_platform)
        fontScaleSeekBar = view.findViewById(R.id.settings_appearance_font_scaling_seekbar)
        fontScaleReset = view.findViewById(R.id.settings_appearance_font_scaling_reset)
        holyLight = view.findViewById(R.id.settings_appearance_holy_light)

        val chatItem = view.findViewById<View>(R.id.chat_item)
        previewAvatar = chatItem.findViewById(R.id.chat_list_adapter_item_text_avatar)
        previewName = chatItem.findViewById(R.id.chat_list_adapter_item_text_name)
        previewTag = chatItem.findViewById(R.id.chat_list_adapter_item_text_tag)
        previewTimestamp = chatItem.findViewById(R.id.chat_list_adapter_item_text_timestamp)
        previewText = chatItem.findViewById(R.id.chat_list_adapter_item_text)

        themeRadioManager = RadioManager(listOf(lightRadio, darkRadio))

        fontScaleSeekBar.max = SettingsAppearanceViewModel.MAX_FONT_SCALING -
            SettingsAppearanceViewModel.MIN_FONT_SCALING
        fontScaleSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val fontScale = progress + SettingsAppearanceViewModel.MIN_FONT_SCALING
                    viewModel.onFontScaleChanged(fontScale)
                    fontScalePlatform.text = fontScaleString(fontScale, useSystemFontScale = false)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
        fontScaleReset.setOnClickListener { viewModel.resetFontScale() }

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

        val isDark = viewState.currentTheme != ModelUserSettings.THEME_LIGHT
        themeRadioManager.setChecked(if (isDark) darkRadio else lightRadio)
        lightRadio.setContainerClickListener { viewModel.onLightThemeTapped() }
        darkRadio.setContainerClickListener {
            viewModel.onDarkThemeTapped(viewState.canSeePureEvil)
        }

        val isPureEvil = viewState.currentTheme == ModelUserSettings.THEME_PURE_EVIL
        // Reference quirk, reproduced: once the hidden theme is on, the row is dimmed and its
        // container stops responding. The switch itself still toggles, because
        // `setDisabledTooltip` only replaces the container's click listener.
        if (isPureEvil) pureEvilSwitch.setDisabledTooltip(null)
        pureEvilSwitch.setOnCheckedListener(null)
        pureEvilSwitch.isChecked = isPureEvil
        pureEvilSwitch.visibility = if (viewState.canSeePureEvil) View.VISIBLE else View.GONE
        pureEvilSwitch.setOnCheckedListener(
            Action1 { checked -> viewModel.setPureEvilEnabled(checked, viewState.currentTheme) },
        )

        syncSwitch.setOnCheckedListener(null)
        syncSwitch.isChecked = viewState.isThemeSyncEnabled
        syncSwitch.setOnCheckedListener(Action1 { viewModel.setThemeSyncEnabled(it) })

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
        fontScalePlatform.text = fontScaleString(effectiveScale, useSystemFontScale)
        fontScaleSeekBar.progress = effectiveScale - SettingsAppearanceViewModel.MIN_FONT_SCALING
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
        IconUtils.setIcon(previewAvatar, viewState.meUser, R.dimen.avatar_size_standard)
        previewTag.visibility = View.GONE
        previewName.text = viewState.meUser.username
        previewTimestamp.text = TimeUtils.toReadableTimeString(
            requireContext(),
            ClockFactory.get().currentTimeMillis(),
        )
        previewText.text = getString(R.string.user_settings_appearance_preview_message_1)
    }

    private fun handleEvent(event: SettingsAppearanceViewModel.Event) = when (event) {
        is SettingsAppearanceViewModel.Event.ShowHolyLight -> showHolyLight()

        is SettingsAppearanceViewModel.Event.PureEvilHint -> toast(
            getString(R.string.theme_pure_evil_easter_hint, event.remaining.toString()),
        )

        is SettingsAppearanceViewModel.Event.PureEvilRevealed ->
            toast(getString(R.string.theme_pure_evil_easter_reveal))

        is SettingsAppearanceViewModel.Event.ThemeUpdated ->
            toast(getString(event.messageResId))
    }

    private fun toast(message: CharSequence) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun showHolyLight() {
        toast(getString(R.string.theme_holy_light_reveal))
        setTorchMode(true)
        holyLight.visibility = View.VISIBLE
        Observable.timer(HOLY_LIGHT_DURATION_SECONDS, TimeUnit.SECONDS)
            .ui(this)
            .subscribe {
                holyLight.visibility = View.GONE
                setTorchMode(false)
            }
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
