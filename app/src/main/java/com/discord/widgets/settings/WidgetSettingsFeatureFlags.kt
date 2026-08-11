package com.discord.widgets.settings

import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsFeatureFlagsBinding
import com.discord.utilities.feature.FeatureFlags

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
        binding.settingsFeatureDisableHeadlessGateway.apply {
            isChecked = FeatureFlags.disableHeadlessGatewayEnabled()
            setOnCheckedListener(FeatureFlags::setDisableHeadlessGatewayEnabled)
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
