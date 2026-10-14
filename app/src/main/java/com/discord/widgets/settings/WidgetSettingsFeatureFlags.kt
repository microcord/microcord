package com.discord.widgets.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppToast
import com.discord.app.ClientSettingsRestoreActivity
import com.discord.databinding.WidgetSettingsFeatureFlagsBinding
import com.discord.stores.StoreStream
import com.discord.utilities.display.setMaximumRefreshRate
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.feature.ManticordSettingsBackup
import com.discord.utilities.feature.ManticordSettingsBackupCodec
import com.discord.utilities.feature.PluginSurfaceCustomization.Surface
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.notice.WidgetNoticeDialog
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.CancellationException
import rx.Subscription

class WidgetSettingsFeatureFlags : AppFragment(R.layout.widget_settings_feature_flags) {
    private var binding: WidgetSettingsFeatureFlagsBinding? = null
    private var backupRequest: Subscription? = null
    private var backupBusy = false

    private enum class BackupAction(val label: Int, val confirmation: Int) {
        SAVE(R.string.cloud_settings_save, R.string.cloud_settings_save_confirm),
        RESTORE(R.string.cloud_settings_restore, R.string.cloud_settings_restore_confirm),
        CLEAR(R.string.cloud_settings_clear, R.string.cloud_settings_clear_confirm),
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.feature_flags)
        setActionBarDisplayHomeAsUpEnabled()
        val binding = WidgetSettingsFeatureFlagsBinding.bind(view).also { this.binding = it }
        binding.settingsFeatureCloudBackup.apply {
            isChecked = FeatureFlags.cloudSettingsBackupEnabled()
            setOnCheckedListener { enabled ->
                FeatureFlags.setCloudSettingsBackupEnabled(enabled)
                if (!enabled) {
                    backupRequest?.unsubscribe()
                    backupRequest = null
                    backupBusy = false
                }
                configureBackup()
            }
        }
        binding.settingsCloudSave.setOnClickListener { confirmBackup(BackupAction.SAVE) }
        binding.settingsCloudRestore.setOnClickListener { confirmBackup(BackupAction.RESTORE) }
        binding.settingsCloudClear.setOnClickListener { confirmBackup(BackupAction.CLEAR) }
        configureBackup()
        binding.settingsFeatureVoiceMessages.apply {
            isChecked = FeatureFlags.voiceMessagesEnabled()
            setOnCheckedListener(FeatureFlags::setVoiceMessagesEnabled)
        }
        binding.settingsFeatureTapTap.apply {
            isChecked = FeatureFlags.tapTapEnabled()
            setOnCheckedListener { enabled ->
                FeatureFlags.setTapTapEnabled(enabled)
                binding.settingsFeatureTapTapReplyOwn.visibility = if (enabled) View.VISIBLE else View.GONE
            }
        }
        binding.settingsFeatureNoReplyPing.apply {
            isChecked = FeatureFlags.noReplyPingEnabled()
            setOnCheckedListener(FeatureFlags::setNoReplyPingEnabled)
        }
        binding.settingsFeatureSwipeToReply.apply {
            isChecked = FeatureFlags.swipeToReplyEnabled()
            setOnCheckedListener(FeatureFlags::setSwipeToReplyEnabled)
        }
        binding.settingsFeatureTapTapReplyOwn.apply {
            visibility = if (FeatureFlags.tapTapEnabled()) View.VISIBLE else View.GONE
            isChecked = FeatureFlags.tapTapReplyOwnEnabled()
            setOnCheckedListener(FeatureFlags::setTapTapReplyOwnEnabled)
        }
        binding.settingsFeatureFetchUncachedUsers.apply {
            isChecked = FeatureFlags.fetchUncachedUsersEnabled()
            setOnCheckedListener(FeatureFlags::setFetchUncachedUsersEnabled)
        }
        binding.settingsFeatureViewProfileImages.apply {
            isChecked = FeatureFlags.viewProfileImagesEnabled()
            setOnCheckedListener(FeatureFlags::setViewProfileImagesEnabled)
        }
        binding.settingsFeatureDisableServerProfiles.apply {
            isChecked = FeatureFlags.disableServerProfilesEnabled()
            setOnCheckedListener(FeatureFlags::setDisableServerProfilesEnabled)
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
        binding.settingsFeatureGifUrlToInput.apply {
            isChecked = FeatureFlags.gifUrlToInputEnabled()
            setOnCheckedListener(FeatureFlags::setGifUrlToInputEnabled)
        }
        binding.settingsFeatureQuickMessageActions.apply {
            isChecked = FeatureFlags.quickMessageActionsEnabled()
            setOnCheckedListener(FeatureFlags::setQuickMessageActionsEnabled)
        }
        binding.settingsFeatureMessageLimitCounter.apply {
            isChecked = FeatureFlags.messageLimitCounterEnabled()
            setOnCheckedListener(FeatureFlags::setMessageLimitCounterEnabled)
        }
        binding.settingsFeatureNitroSpoof.apply {
            isChecked = FeatureFlags.nitroSpoofEnabled()
            setOnCheckedListener(FeatureFlags::setNitroSpoofEnabled)
        }
        binding.settingsFeatureCapitalizeSentences.apply {
            isChecked = FeatureFlags.capitalizeSentencesEnabled()
            setOnCheckedListener(FeatureFlags::setCapitalizeSentencesEnabled)
        }
        binding.settingsFeatureSilentTyping.apply {
            isChecked = FeatureFlags.silentTypingEnabled()
            setOnCheckedListener(FeatureFlags::setSilentTypingEnabled)
        }
        binding.settingsFeatureSmoothKeyboard.apply {
            isChecked = FeatureFlags.smoothKeyboardEnabled()
            setOnCheckedListener(FeatureFlags::setSmoothKeyboardEnabled)
        }
        binding.settingsFeatureMaximumRefreshRate.apply {
            isChecked = FeatureFlags.maximumRefreshRateEnabled()
            setOnCheckedListener { enabled ->
                FeatureFlags.setMaximumRefreshRateEnabled(enabled)
                requireActivity().window.setMaximumRefreshRate(enabled)
            }
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
        binding.settingsFeatureCustomizeDmList.setOnClickListener {
            WidgetSettingsDmListCustomization.launch(requireContext())
        }
        binding.settingsFeatureCustomizeChannelSidebar.setOnClickListener {
            WidgetSettingsChannelSidebarCustomization.launch(requireContext())
        }
        binding.settingsFeatureCustomizeBottomTabs.setOnClickListener {
            WidgetSettingsPluginSurfaceCustomization.launch(requireContext(), Surface.BOTTOM_TABS)
        }
        binding.settingsFeatureCustomizeProfilePopouts.setOnClickListener {
            WidgetSettingsPluginSurfaceCustomization.launch(requireContext(), Surface.PROFILE)
        }
        binding.settingsFeatureCustomizeProfileTabs.setOnClickListener {
            WidgetSettingsPluginSurfaceCustomization.launch(requireContext(), Surface.PROFILE_TABS)
        }
        binding.settingsFeatureMessageCustomizations.apply {
            isChecked = FeatureFlags.messageCustomizationsEnabled()
            setOnCheckedListener { enabled ->
                FeatureFlags.setMessageCustomizationsEnabled(enabled)
                Snackbar.make(binding.root, R.string.user_settings_restart_app_mobile, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.bundle_ready_restart) { restartApp(requireContext()) }
                    .show()
            }
        }
        binding.settingsFeatureCustomizeMessages.setOnClickListener {
            WidgetSettingsMessageCustomization.launch(requireContext())
        }
    }

    private fun configureBackup() {
        val bound = binding ?: return
        val enabled = FeatureFlags.cloudSettingsBackupEnabled()
        listOf(bound.settingsCloudSave, bound.settingsCloudRestore, bound.settingsCloudClear).forEach {
            it.isVisible = enabled
            it.isEnabled = !backupBusy
            it.alpha = if (backupBusy) 0.5f else 1f
        }
        bound.settingsCloudStatus.isVisible = enabled && backupBusy
    }

    private fun confirmBackup(action: BackupAction) {
        if (backupBusy || !FeatureFlags.cloudSettingsBackupEnabled()) return
        WidgetNoticeDialog.show(
            parentFragmentManager,
            headerText = getString(R.string.cloud_settings_title),
            bodyText = getString(action.confirmation),
            okText = getString(action.label),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { startBackup(action) }),
        )
    }

    private fun startBackup(action: BackupAction) {
        if (backupBusy || !FeatureFlags.cloudSettingsBackupEnabled()) return
        val restore = action == BackupAction.RESTORE
        val auth = StoreStream.getAuthentication().getAuthState() ?: return
        val app = requireContext().applicationContext
        if (restore && ManticordSettingsBackup.hasPendingRestore(app)) {
            startActivity(Intent(requireContext(), ClientSettingsRestoreActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            return
        }
        val backup = ManticordSettingsBackup(RestAPI.api) {
            FeatureFlags.cloudSettingsBackupEnabled() && StoreStream.getAuthentication().getAuthState() === auth
        }
        val operation = when (action) {
            BackupAction.SAVE -> backup.save(StoreStream.captureClientSettings(app))
            BackupAction.CLEAR -> backup.clear()
            BackupAction.RESTORE -> {
                val owner = ManticordSettingsBackup.authFingerprint()
                backup.load().map { payload ->
                    backup.checkPermitted()
                    ManticordSettingsBackup.stageRestore(app, payload, owner)
                    Unit
                }
            }
        }
        backupBusy = true
        configureBackup()
        operation.restSubscribeOn(retry = false).ui(this).appSubscribe(
            WidgetSettingsFeatureFlags::class.java, context = context,
            subscriptionHandler = { backupRequest = it },
            errorHandler = { error ->
                if (error.cause is IllegalArgumentException || error.cause is IllegalStateException ||
                    error.cause is CancellationException) {
                    error.shouldLog = false
                    error.showErrorToasts = false
                    if (error.cause !is CancellationException) {
                        val source = error.cause.stackTrace.firstOrNull { it.className.startsWith("com.discord.utilities.feature.") }
                        AppLog.w("CloudSettings", "action=${action.name.lowercase()} error=${error.cause.javaClass.simpleName} source=$source")
                        AppToast.show(this, when {
                            action == BackupAction.CLEAR -> R.string.cloud_settings_clear_failed
                            error.cause is ManticordSettingsBackupCodec.UnrecognizedCloudData -> R.string.cloud_settings_existing_data
                            error.cause is IllegalArgumentException -> if (restore) R.string.cloud_settings_invalid else R.string.cloud_settings_save_invalid
                            else -> R.string.cloud_settings_conflict
                        })
                    }
                }
            },
            onTerminated = { backupRequest = null; backupBusy = false; configureBackup() },
            onNext = {
                if (binding != null && FeatureFlags.cloudSettingsBackupEnabled() &&
                    StoreStream.getAuthentication().getAuthState() === auth) {
                    if (restore) startActivity(Intent(requireContext(), ClientSettingsRestoreActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    else AppToast.show(this, if (action == BackupAction.CLEAR) R.string.cloud_settings_cleared else R.string.cloud_settings_saved)
                }
            },
        )
    }

    override fun onDestroyView() {
        backupRequest?.unsubscribe()
        backupRequest = null
        backupBusy = false
        binding = null
        super.onDestroyView()
    }
}

internal fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launch.component?.let(Intent::makeRestartActivityTask)?.let(context::startActivity)
    (context as? Activity)?.finishAffinity()
}
