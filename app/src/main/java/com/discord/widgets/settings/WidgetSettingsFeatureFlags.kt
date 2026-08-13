package com.discord.widgets.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsFeatureFlagsBinding
import com.discord.utilities.feature.FeatureFlags
import com.google.android.material.snackbar.Snackbar

class WidgetSettingsFeatureFlags : AppFragment(R.layout.widget_settings_feature_flags) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.feature_flags)
        setActionBarDisplayHomeAsUpEnabled()
        val binding = WidgetSettingsFeatureFlagsBinding.bind(view)
        binding.settingsFeatureVoiceMessages.apply {
            isChecked = FeatureFlags.voiceMessagesEnabled()
            setOnCheckedListener(FeatureFlags::setVoiceMessagesEnabled)
        }
        binding.settingsFeatureTapTap.apply {
            isChecked = FeatureFlags.tapTapEnabled()
            setOnCheckedListener(FeatureFlags::setTapTapEnabled)
        }
        binding.settingsFeatureFetchUncachedUsers.apply {
            isChecked = FeatureFlags.fetchUncachedUsersEnabled()
            setOnCheckedListener(FeatureFlags::setFetchUncachedUsersEnabled)
        }
        binding.settingsFeatureViewProfileImages.apply {
            isChecked = FeatureFlags.viewProfileImagesEnabled()
            setOnCheckedListener(FeatureFlags::setViewProfileImagesEnabled)
        }
        binding.settingsFeatureTypingUserAvatars.apply {
            isChecked = FeatureFlags.typingUserAvatarsEnabled()
            setOnCheckedListener(FeatureFlags::setTypingUserAvatarsEnabled)
        }
        binding.settingsFeatureChatHeaderStatusIndicators.apply {
            isChecked = FeatureFlags.chatHeaderStatusIndicatorsEnabled()
            setOnCheckedListener(FeatureFlags::setChatHeaderStatusIndicatorsEnabled)
        }
        binding.settingsFeatureMoreGifProviders.apply {
            isChecked = FeatureFlags.moreGifProvidersEnabled()
            setOnCheckedListener(FeatureFlags::setMoreGifProvidersEnabled)
        }
        binding.settingsFeatureMessageLimitCounter.apply {
            isChecked = FeatureFlags.messageLimitCounterEnabled()
            setOnCheckedListener(FeatureFlags::setMessageLimitCounterEnabled)
        }
        binding.settingsFeatureChatboxCustomizations.apply {
            isChecked = FeatureFlags.chatboxCustomizationsEnabled()
            setOnCheckedListener { enabled ->
                FeatureFlags.setChatboxCustomizationsEnabled(enabled)
                Snackbar.make(binding.root, R.string.user_settings_restart_app_mobile, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.bundle_ready_restart) { restartApp(requireContext()) }
                    .show()
            }
        }
        binding.settingsFeatureCustomizeChatInput.setOnClickListener {
            WidgetSettingsChatInputCustomization.launch(requireContext())
        }
        binding.settingsFeatureHeaderCustomizations.apply {
            isChecked = FeatureFlags.headerCustomizationsEnabled()
            setOnCheckedListener { enabled ->
                FeatureFlags.setHeaderCustomizationsEnabled(enabled)
                Snackbar.make(binding.root, R.string.user_settings_restart_app_mobile, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.bundle_ready_restart) { restartApp(requireContext()) }
                    .show()
            }
        }
        binding.settingsFeatureCustomizeHeader.setOnClickListener {
            WidgetSettingsHeaderCustomization.launch(requireContext())
        }
        binding.settingsFeatureAvatarDecorations.apply {
            isChecked = FeatureFlags.avatarDecorationsEnabled()
            setOnCheckedListener(FeatureFlags::setAvatarDecorationsEnabled)
        }
        binding.settingsFeatureNameplates.apply {
            isChecked = FeatureFlags.nameplatesEnabled()
            setOnCheckedListener(FeatureFlags::setNameplatesEnabled)
        }
        binding.settingsFeatureProfileEffects.apply {
            isChecked = FeatureFlags.profileEffectsEnabled()
            setOnCheckedListener(FeatureFlags::setProfileEffectsEnabled)
        }
        binding.settingsFeatureProfileFrames.apply {
            isChecked = FeatureFlags.profileFramesEnabled()
            setOnCheckedListener(FeatureFlags::setProfileFramesEnabled)
        }
    }

}

internal fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launch.component?.let(Intent::makeRestartActivityTask)?.let(context::startActivity)
    (context as? Activity)?.finishAffinity()
}
