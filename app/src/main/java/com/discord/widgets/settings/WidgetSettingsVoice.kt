package com.discord.widgets.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.widget.NestedScrollView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppFragment
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreStream
import com.discord.utilities.accessibility.AccessibilityUtils
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton
import rx.Observable
import rx.functions.Action1
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * "Voice & Video" user settings.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsVoice`. Everything on this screen writes
 * through `StoreMediaSettings`, which persists the whole [StoreMediaSettings.VoiceConfiguration] and
 * pushes it into the native engine; the screen holds no state of its own.
 *
 * Two conventions are easy to get wrong and are reproduced deliberately:
 * - **The output-volume and sensitivity sliders are not linear in the stored value.** Volume is
 *   linear in *perceived loudness* and is converted to amplitude on write; sensitivity is stored in
 *   dBFS (−100…0) and is offset by 100 to fit a progress bar.
 * - **Noise suppression is mutually exclusive with noise cancellation.** When Krisp cancellation is
 *   on, the suppression row is not hidden — it is left visible with a disabled tooltip explaining
 *   why it cannot be turned on.
 *
 * ### Degraded pending `StoreStream.getMediaEngine()`
 * `StoreMediaEngine` exists but `StoreStream` exposes no accessor for it (RTC transport is still
 * Phase 11). Three things therefore cannot work and are hidden rather than shown dead:
 * the **OpenSL ES** section (`getOpenSLESConfig`/`setOpenSLESConfig`), the **microphone test** and
 * the **live input meter** (`getLocalVoiceStatus`). The manual sensitivity slider still works,
 * because it only writes. See `docs/parity-audit/followups.md`.
 */
class WidgetSettingsVoice : AppFragment(R.layout.widget_settings_voice) {

    private val viewModel: SettingsVoiceViewModel by appViewModels { SettingsVoiceViewModel() }

    private lateinit var inputModeRow: RelativeLayout
    private lateinit var inputModeValue: TextView
    private lateinit var automaticVadToggle: CheckedSetting
    private lateinit var sensitivityWrap: RelativeLayout
    private lateinit var sensitivityTestButton: MaterialButton
    private lateinit var sensitivityTestingContainer: LinearLayout
    private lateinit var sensitivityManual: SeekBar
    private lateinit var sensitivityAutomatic: View
    private lateinit var sensitivityLabel: TextView
    private lateinit var outputVolume: SeekBar
    private lateinit var troubleshootingGuide: TextView
    private lateinit var overlayToggle: CheckedSetting
    private lateinit var noiseCancellationToggle: CheckedSetting
    private lateinit var krispInfo: TextView
    private lateinit var echoCancellationToggle: CheckedSetting
    private lateinit var noiseSuppressionToggle: CheckedSetting
    private lateinit var gainControlToggle: CheckedSetting
    private lateinit var krispVadToggle: CheckedSetting
    private lateinit var videoHardwareScalingToggle: CheckedSetting
    private lateinit var openSlesHeader: TextView
    private lateinit var openSlesHelp: TextView
    private lateinit var openSlesRadios: List<CheckedSetting>

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // Title and subtitle come from the toolbar declared in the layout.
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)

        inputModeRow = view.findViewById(R.id.settings_voice_mode)
        inputModeValue = view.findViewById(R.id.settings_voice_mode_value)
        automaticVadToggle = view.findViewById(R.id.settings_voice_auto_vad_toggle)
        sensitivityWrap = view.findViewById(R.id.settings_voice_sensitivity_wrap)
        sensitivityTestButton = view.findViewById(R.id.settings_voice_sensitivity_test_button)
        sensitivityTestingContainer =
            view.findViewById(R.id.settings_voice_sensitivity_testing_container)
        sensitivityManual = view.findViewById(R.id.settings_voice_sensitivity_manual)
        sensitivityAutomatic = view.findViewById(R.id.settings_voice_sensitivity_automatic)
        sensitivityLabel = view.findViewById(R.id.settings_voice_sensitivity_label)
        outputVolume = view.findViewById(R.id.settings_voice_output_volume)
        troubleshootingGuide = view.findViewById(R.id.settings_voice_video_troubleshooting_guide)
        overlayToggle = view.findViewById(R.id.settings_voice_overlay_toggle)
        noiseCancellationToggle = view.findViewById(R.id.settings_voice_noise_cancellation_toggle)
        krispInfo = view.findViewById(R.id.settings_voice_krisp_info)
        echoCancellationToggle = view.findViewById(R.id.settings_voice_echo_cancellation_toggle)
        noiseSuppressionToggle = view.findViewById(R.id.settings_voice_noise_suppression_toggle)
        gainControlToggle = view.findViewById(R.id.settings_voice_gain_control_toggle)
        krispVadToggle = view.findViewById(R.id.settings_voice_krisp_vad_toggle)
        videoHardwareScalingToggle =
            view.findViewById(R.id.settings_voice_enable_hardware_scaling_toggle)
        openSlesHeader = view.findViewById(R.id.settings_voice_opensl_header)
        openSlesHelp = view.findViewById(R.id.settings_voice_opensles_help)
        openSlesRadios = listOf(
            view.findViewById(R.id.settings_voice_opensles_default),
            view.findViewById(R.id.settings_voice_opensles_force_enabled),
            view.findViewById(R.id.settings_voice_opensles_force_disabled),
        )

        openSlesHelp.text =
            SettingsStrings.render(view.context, R.string.form_label_android_opensl_desc)
        troubleshootingGuide.text = SettingsStrings.render(
            view.context,
            R.string.form_help_voice_video_troubleshooting_guide,
            "guideURL" to VOICE_VIDEO_TROUBLESHOOTING_URL,
        )
        krispInfo.text = SettingsStrings.render(
            view.context,
            R.string.learn_more_link,
            "helpdeskArticle" to KRISP_HELP_URL,
        )
        krispInfo.movementMethod = LinkMovementMethod.getInstance()

        configureOverlayToggle()
        configureOpenSlEs()

        listOf(
            view.findViewById<View>(R.id.settings_voice_input_header),
            view.findViewById(R.id.settings_voice_output_header),
            view.findViewById(R.id.settings_voice_overlay_header),
            view.findViewById(R.id.settings_voice_processing_header),
            view.findViewById(R.id.settings_voice_video_header),
            view.findViewById(R.id.settings_voice_opensl_header),
        ).forEach(AccessibilityUtils::setViewIsHeading)

        val targetResId = mostRecentIntent.getIntExtra(ARG_TARGET_RES_ID, 0)
        if (targetResId != 0) {
            val target = view.findViewById<View>(targetResId)
            target.post {
                (target.parent?.parent as? NestedScrollView)?.smoothScrollTo(0, target.top)
                if (target === overlayToggle &&
                    mostRecentIntent.getBooleanExtra(ARG_TARGET_AUTO_TOGGLE, false) &&
                    !overlayToggle.isChecked
                ) {
                    overlayToggle.setChecked(true, true)
                }
            }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        outputVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    // Only user drags write; programmatic updates would echo straight back.
                    if (!fromUser) return
                    StoreStream.getMediaSettings()
                        .setOutputVolume(perceptualToAmplitude(progress.toFloat()))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            },
        )
        sensitivityManual.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    // The bar spans 0..100 but the setting is dBFS in −100..0.
                    StoreStream.getMediaSettings()
                        .setSensitivity(progress.toFloat() - SENSITIVITY_DBFS_OFFSET)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            },
        )
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    private fun configureUI(viewState: SettingsVoiceViewModel.ViewState) {
        val config = viewState.voiceConfig

        outputVolume.progress = amplitudeToPerceptual(config.outputVolume).roundToInt()

        inputModeRow.setOnClickListener {
            InputModeSelector().show(childFragmentManager, InputModeSelector::class.java.name)
        }
        inputModeValue.setText(
            when (config.inputMode) {
                MediaEngineConnection.InputMode.PUSH_TO_TALK -> R.string.input_mode_ptt
                MediaEngineConnection.InputMode.VOICE_ACTIVITY -> R.string.input_mode_vad
            },
        )

        gainControlToggle.setChecked(config.automaticGainControl, false)
        gainControlToggle.setOnCheckedListener(
            Action1 { StoreStream.getMediaSettings().toggleAutomaticGainControl() },
        )

        noiseCancellationToggle.setChecked(
            config.noiseProcessing == StoreMediaSettings.NoiseProcessing.Cancellation,
            false,
        )
        noiseCancellationToggle.setOnCheckedListener(
            Action1 { StoreStream.getMediaSettings().toggleNoiseCancellation() },
        )

        noiseSuppressionToggle.setChecked(
            config.noiseProcessing == StoreMediaSettings.NoiseProcessing.Suppression,
            false,
        )
        if (config.noiseProcessing == StoreMediaSettings.NoiseProcessing.Cancellation) {
            // Krisp cancellation already owns the input; suppression stays visible but explains
            // itself rather than silently doing nothing.
            noiseSuppressionToggle.setDisabledTooltip(R.string.user_settings_disable_noise_suppression)
            noiseSuppressionToggle.setSubtext(
                getString(R.string.user_settings_disable_noise_suppression),
            )
        } else {
            noiseSuppressionToggle.setContainerClickListener {
                StoreStream.getMediaSettings().toggleNoiseSuppression()
            }
            noiseSuppressionToggle.setSubtext(null)
        }

        krispVadToggle.setChecked(
            config.vadUseKrisp == StoreMediaSettings.VadUseKrisp.Enabled,
            false,
        )
        krispVadToggle.setOnCheckedListener(
            Action1 { StoreStream.getMediaSettings().toggleVADUseKrisp() },
        )

        echoCancellationToggle.setChecked(config.echoCancellation, false)
        echoCancellationToggle.setOnCheckedListener(
            Action1 { StoreStream.getMediaSettings().toggleEchoCancellation() },
        )

        automaticVadToggle.setChecked(config.automaticVad, false)
        // Automatic voice activity only means anything in voice-activity mode.
        automaticVadToggle.visibility = if (viewState.isVoiceActivityMode) View.VISIBLE else View.GONE
        automaticVadToggle.setOnCheckedListener(
            Action1 { StoreStream.getMediaSettings().toggleAutomaticVAD() },
        )

        videoHardwareScalingToggle.setChecked(config.enableVideoHardwareScaling, false)
        videoHardwareScalingToggle.setOnCheckedListener(
            Action1 { StoreStream.getMediaSettings().toggleEnableVideoHardwareScaling() },
        )

        configureVoiceSensitivity(viewState)
    }

    private fun configureVoiceSensitivity(viewState: SettingsVoiceViewModel.ViewState) {
        val config = viewState.voiceConfig
        sensitivityWrap.visibility = if (viewState.isVoiceActivityMode) View.VISIBLE else View.GONE
        sensitivityLabel.visibility = if (config.automaticVad) View.VISIBLE else View.GONE

        // The reference shows the meter and hides the "Test microphone" button once the native
        // engine has produced a voice status. Without `StoreStream.getMediaEngine()` there is no
        // status to observe, so neither the meter nor the test button can do anything: both stay
        // hidden rather than sitting inert. See the class KDoc.
        sensitivityTestingContainer.visibility =
            if (config.automaticVad) View.GONE else View.VISIBLE
        sensitivityTestButton.visibility = View.GONE

        if (config.automaticVad) {
            // Automatic mode has nothing to drag; the reference shows a live speaking indicator here.
            sensitivityAutomatic.visibility = View.GONE
            sensitivityManual.visibility = View.GONE
        } else {
            sensitivityAutomatic.visibility = View.GONE
            sensitivityManual.visibility = View.VISIBLE
            sensitivityManual.progress =
                (config.sensitivity + SENSITIVITY_DBFS_OFFSET).roundToInt()
        }
    }

    private fun configureOpenSlEs() {
        // `StoreMediaEngine.getOpenSLESConfig()`/`setOpenSLESConfig()` are unreachable — see the
        // class KDoc. Three radios that cannot read or write the engine would be worse than absent.
        openSlesHeader.visibility = View.GONE
        openSlesHelp.visibility = View.GONE
        openSlesRadios.forEach { it.visibility = View.GONE }
    }

    private fun configureOverlayToggle() {
        val userSettings = StoreStream.getUserSettings()
        overlayToggle.setChecked(
            userSettings.getIsMobileOverlayEnabled() && hasOverlayPermission(requireContext()),
            false,
        )
        overlayToggle.setOnCheckedListener(
            Action1 { isChecked ->
                if (isChecked && !hasOverlayPermission(requireContext())) {
                    // The switch cannot commit until the system grants the permission.
                    overlayToggle.setChecked(false, false)
                    AnalyticsTracker.permissionsRequested("overlay")
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${requireContext().packageName}"),
                        ),
                        OVERLAY_PERMISSION_REQUEST_CODE,
                    )
                } else {
                    userSettings.setIsMobileOverlayEnabled(isChecked)
                    onOverlayToggled()
                }
            },
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != OVERLAY_PERMISSION_REQUEST_CODE) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        // The system dialog reports nothing useful, so the permission is re-read directly.
        val granted = hasOverlayPermission(requireContext())
        StoreStream.getUserSettings().setIsMobileOverlayEnabled(granted)
        overlayToggle.setChecked(granted, false)
        onOverlayToggled()
    }

    private fun onOverlayToggled() {
        // The reference starts/stops `DiscordOverlayService` here, which does not exist in this
        // project yet (only the abstract `com.discord.overlay.OverlayService`). The preference is
        // persisted correctly, so the overlay comes up on the next voice connection once that
        // service lands; it does not appear or disappear immediately. See the class KDoc.
    }

    /**
     * The push-to-talk / voice-activity chooser.
     *
     * Reference: `WidgetSettingsVoice.InputModeSelector`. It writes and dismisses; the parent screen
     * re-renders from the store rather than being told directly.
     */
    class InputModeSelector : AppBottomSheet() {

        override fun getContentViewResId(): Int = R.layout.widget_settings_voice_input_mode

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            setBottomSheetCollapsedStateDisabled()
            view.findViewById<View>(R.id.settings_voice_input_mode_ptt).setOnClickListener {
                StoreStream.getMediaSettings()
                    .setVoiceInputMode(MediaEngineConnection.InputMode.PUSH_TO_TALK)
                dismiss()
            }
            view.findViewById<View>(R.id.settings_voice_input_mode_vad).setOnClickListener {
                StoreStream.getMediaSettings()
                    .setVoiceInputMode(MediaEngineConnection.InputMode.VOICE_ACTIVITY)
                dismiss()
            }
        }
    }

    companion object {
        private const val ARG_TARGET_RES_ID = "ARG_TARGET_RES_ID"
        private const val ARG_TARGET_AUTO_TOGGLE = "ARG_TARGET_AUTO_TOGGLE"
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 2552

        /** Sensitivity is stored as dBFS in −100..0; the seek bar spans 0..100. */
        private const val SENSITIVITY_DBFS_OFFSET = 100f

        private const val VOLUME_DYNAMIC_RANGE_DB = 50f
        private const val VOLUME_BOOST_DYNAMIC_RANGE_DB = 6f
        private const val NORMALIZED_MAX_VOLUME = 100f

        private const val KRISP_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/360040843952"
        private const val VOICE_VIDEO_TROUBLESHOOTING_URL =
            "https://support.discord.com/hc/en-us/articles/360045138471" +
                "?utm_source=discord&utm_medium=blog&utm_campaign=2020-06_help-voice-video" +
                "&utm_content=--t%3Apm"

        /**
         * Below API 23 an app cannot be denied the overlay permission, so it is always granted.
         */
        private fun hasOverlayPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1 ||
                Settings.canDrawOverlays(context)

        /**
         * Amplitude → slider position.
         *
         * Port of the reference's `utilities/voice/PerceptualVolumeUtils`, which this project does
         * not have yet. A slider is linear in *perceived* loudness: below the nominal maximum it
         * spans a 50 dB range ending at 0 dB, above it a 6 dB boost range. **Belongs in
         * `utilities/voice`** — `StoreAudioManagerV2` already carries a private copy of the inverse.
         */
        internal fun amplitudeToPerceptual(
            amplitude: Float,
            normalizedMax: Float = NORMALIZED_MAX_VOLUME,
        ): Float {
            if (amplitude <= 0f) return 0f
            val decibels = log10(amplitude / normalizedMax) * 20f
            val perceptual = if (decibels > 0f) {
                decibels / VOLUME_BOOST_DYNAMIC_RANGE_DB + 1f
            } else {
                (decibels + VOLUME_DYNAMIC_RANGE_DB) / VOLUME_DYNAMIC_RANGE_DB
            }
            return normalizedMax * perceptual
        }

        /** Slider position → amplitude; the inverse of [amplitudeToPerceptual]. */
        internal fun perceptualToAmplitude(
            perceptual: Float,
            normalizedMax: Float = NORMALIZED_MAX_VOLUME,
        ): Float {
            if (perceptual <= 0f) return 0f
            val decibels = if (perceptual > normalizedMax) {
                (perceptual - normalizedMax) / normalizedMax * VOLUME_BOOST_DYNAMIC_RANGE_DB
            } else {
                perceptual / normalizedMax * VOLUME_DYNAMIC_RANGE_DB - VOLUME_DYNAMIC_RANGE_DB
            }
            return normalizedMax * 10f.pow(decibels / 20f)
        }

        @JvmOverloads
        fun launch(
            context: Context,
            @IdRes targetResId: Int? = null,
            isTargetAutoToggle: Boolean = false,
        ) {
            SettingsNavigation.launch(
                context,
                WidgetSettingsVoice::class.java,
                Intent().apply {
                    targetResId?.let { putExtra(ARG_TARGET_RES_ID, it) }
                    if (isTargetAutoToggle) putExtra(ARG_TARGET_AUTO_TOGGLE, true)
                },
            )
        }
    }
}

/**
 * State for [WidgetSettingsVoice].
 *
 * Reference: `WidgetSettingsVoice.Model`, which also carries the engine's OpenSL ES config and its
 * live `LocalVoiceStatus`. Neither is reachable without `StoreStream.getMediaEngine()`, so this
 * state is the `StoreMediaSettings` half only — see [WidgetSettingsVoice]'s KDoc.
 *
 * There is no loading state: `StoreMediaSettings.getVoiceConfig()` is backed by a persisted
 * configuration and emits immediately, so the screen is never empty.
 */
class SettingsVoiceViewModel(
    voiceConfig: Observable<StoreMediaSettings.VoiceConfiguration> =
        StoreStream.getMediaSettings().getVoiceConfig(),
) : AppViewModel<SettingsVoiceViewModel.ViewState>() {

    data class ViewState(val voiceConfig: StoreMediaSettings.VoiceConfiguration) {
        val isPushToTalkMode: Boolean
            get() = voiceConfig.inputMode == MediaEngineConnection.InputMode.PUSH_TO_TALK

        val isVoiceActivityMode: Boolean
            get() = voiceConfig.inputMode == MediaEngineConnection.InputMode.VOICE_ACTIVITY
    }

    init {
        voiceConfig.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(ViewState(it))
        }
    }
}
