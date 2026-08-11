package com.discord.widgets.settings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
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
        binding.settingsFeatureBetterChatbox.apply {
            isChecked = FeatureFlags.betterChatboxEnabled()
            setOnCheckedListener { enabled ->
                FeatureFlags.setBetterChatboxEnabled(enabled)
                Snackbar.make(binding.root, R.string.user_settings_restart_app_mobile, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.bundle_ready_restart) { restartApp() }
                    .show()
            }
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

    private fun restartApp() {
        val context = requireContext()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(context, 0, launch, flags)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 200L,
            pending,
        )
        Process.killProcess(Process.myPid())
    }
}
