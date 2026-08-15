package com.discord.widgets.voice.sheet

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.guild.GuildFeature
import com.discord.app.AppBottomSheet
import com.discord.dialogs.audio.AudioOutputSelectionDialog
import com.discord.app.appViewModels
import com.discord.databinding.WidgetVoiceSettingsBottomSheetBinding
import com.discord.databinding.ViewVoiceSettingsRowBinding
import com.discord.databinding.ViewVoiceSettingsMemberRowBinding
import com.discord.i18n.setI18nText
import com.discord.utilities.icon.IconUtils
import com.discord.databinding.ViewVoiceSettingsSwitchRowBinding
import com.discord.models.guild.Guild
import com.discord.stores.StoreMediaSettings
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.channels.settings.WidgetChannelNotificationSettings
import com.discord.widgets.channels.settings.WidgetTextChannelSettings
import com.discord.widgets.chat.input.applauncher.AppLauncherEntrypoint
import com.discord.widgets.chat.input.applauncher.WidgetAppLauncher
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventListBottomSheet
import com.discord.widgets.mobile_reports.WidgetMobileReports
import com.discord.widgets.settings.WidgetSettingsVoice
import com.discord.widgets.stage.sheet.WidgetStageStartEventBottomSheet
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * The overflow menu behind the voice sheet's gear icon: participant visibility, noise
 * cancellation, and links out to invites, stage settings, events, voice settings, channel
 * notifications and reporting.
 */
class WidgetVoiceSettingsBottomSheet : AppBottomSheet() {

    /** What [WidgetVoiceSettingsBottomSheetViewModel] renders. */
    interface ViewState {
        val channel: Channel
        val showVoiceParticipants: Boolean
        val showVoiceParticipantsToggle: Boolean
        val showChannelSettings: Boolean
        val showActivitiesItem: Boolean
        val members: List<VoiceMember>
        val isDeafened: Boolean
        val showSelfVideo: Boolean
        val showInviteItem: Boolean
        val noiseProcessing: StoreMediaSettings.NoiseProcessing
        val isEndToEndEncrypted: Boolean
        val privacyCode: String?
        val isStreamPrivacyCode: Boolean
        val showStageSettings: Boolean
        val showReportItem: Boolean
        val guild: Guild?
    }

    private val channelId: Long by lazy { argumentsOrDefault.getLong(EXTRA_CHANNEL_ID) }

    private val mode: VoiceSettingsMode by lazy {
        VoiceSettingsMode.entries.getOrElse(argumentsOrDefault.getInt(EXTRA_MODE)) { VoiceSettingsMode.MENU }
    }

    private val viewModel: WidgetVoiceSettingsBottomSheetViewModel by appViewModels {
        WidgetVoiceSettingsBottomSheetViewModel(channelId)
    }

    private var _binding: WidgetVoiceSettingsBottomSheetBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun getContentViewResId(): Int = R.layout.widget_voice_settings_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetVoiceSettingsBottomSheetBinding.bind(view)
        setBottomSheetState(BottomSheetBehavior.STATE_EXPANDED)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .subscribe(::configureUI)
    }

    private fun configureUI(viewState: ViewState) {
        val channel = viewState.channel
        val isMenu = mode == VoiceSettingsMode.MENU

        if (isMenu) {
            binding.voiceSettingsSheetTitle.text = channel.getDisplayName()
        } else {
            binding.voiceSettingsSheetTitle.setText(R.string.voice)
        }
        val subtitle = if (isMenu) memberSummary(viewState.members) else null
        binding.voiceSettingsSheetSubtitle.text = subtitle
        binding.voiceSettingsSheetSubtitle.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        binding.voiceSettingsE2ee.visibility =
            if (isMenu && viewState.isEndToEndEncrypted) View.VISIBLE else View.GONE

        val showChannelSettings = isMenu && viewState.showChannelSettings
        binding.voiceSettingsChannelGroup.visibility = if (showChannelSettings) View.VISIBLE else View.GONE
        binding.voiceSettingsChannelSettings.bind(
            label = R.string.channel_settings,
            sublabel = R.string.voice_panel_channel_settings_subtitle,
        ) { navigate { WidgetTextChannelSettings.launch(channelId, it) } }

        val showActivities = !isMenu && viewState.showActivitiesItem
        binding.voiceSettingsActivities.root.visibility = if (showActivities) View.VISIBLE else View.GONE
        binding.voiceSettingsActivitiesDivider.visibility = if (showActivities) View.VISIBLE else View.GONE
        binding.voiceSettingsActivities.bind(
            label = R.string.embedded_activities_start_an_activity,
            icon = R.drawable.ic_activity_24dp,
        ) {
            dismiss()
            WidgetAppLauncher.show(parentFragmentManager, channelId, AppLauncherEntrypoint.VOICE)
        }

        // The menu carries voice settings at the top of its one group; the drawer ends with it.
        listOf(binding.voiceSettingsMenuVoiceSettings, binding.voiceSettingsVoiceSettingsRow).forEach { row ->
            row.bind(
                label = R.string.voice_settings,
                sublabel = R.string.voice_panel_voice_settings_subtitle,
                icon = R.drawable.ic_settings_24dp,
            ) { navigate { context -> WidgetSettingsVoice.launch(context) } }
        }
        binding.voiceSettingsMenuVoiceSettings.root.visibility = if (isMenu) View.VISIBLE else View.GONE
        binding.voiceSettingsMenuVoiceSettingsDivider.visibility = if (isMenu) View.VISIBLE else View.GONE
        binding.voiceSettingsVoiceSettingsGroup.visibility = if (isMenu) View.GONE else View.VISIBLE

        binding.voiceSettingsDeafen.bindSwitch(
            label = R.string.deafen,
            sublabel = R.string.voice_panel_deafen_subtitle,
            icon = R.drawable.ic_headset_deafened_white_24dp,
            checked = viewState.isDeafened,
        ) { viewModel.onToggleDeafen() }

        // Audio output, self video and noise reduction are drawer only upstream.
        binding.voiceSettingsAudioOutput.root.visibility = if (isMenu) View.GONE else View.VISIBLE
        binding.voiceSettingsAudioOutputDivider.visibility = if (isMenu) View.GONE else View.VISIBLE
        binding.voiceSettingsAudioOutput.bind(
            label = R.string.audio_devices_change_output,
            icon = R.drawable.ic_sound_24dp,
        ) { AudioOutputSelectionDialog.show(parentFragmentManager) }

        // The switch reads "only show videos", the inverse of the participant toggle it drives.
        binding.voiceSettingsToggleVideoParticipants.bindSwitch(
            label = R.string.voice_panel_only_show_videos,
            sublabel = R.string.voice_panel_only_show_videos_subtitle,
            icon = R.drawable.ic_videocam_white_24dp,
            checked = !viewState.showVoiceParticipants,
        ) { viewModel.onToggleVoiceParticipantsHidden() }

        binding.voiceSettingsShowSelfVideo.root.visibility = if (isMenu) View.GONE else View.VISIBLE
        binding.voiceSettingsSelfVideoDivider.visibility = if (isMenu) View.GONE else View.VISIBLE
        binding.voiceSettingsShowSelfVideo.bindSwitch(
            label = R.string.voice_panel_show_self_video,
            icon = R.drawable.ic_videocam_white_24dp,
            checked = viewState.showSelfVideo,
        ) { viewModel.onToggleSelfVideoHidden() }

        val showPrivacyCode = isMenu && viewState.privacyCode != null
        binding.voiceSettingsPrivacyCode.root.visibility = if (showPrivacyCode) View.VISIBLE else View.GONE
        binding.voiceSettingsPrivacyCodeDivider.visibility = if (showPrivacyCode) View.VISIBLE else View.GONE
        val isStream = viewState.isStreamPrivacyCode
        binding.voiceSettingsPrivacyCode.bind(
            label = if (isStream) R.string.e2ee_stream_privacy_code else R.string.e2ee_voice_privacy_code,
            sublabel = if (isStream) {
                R.string.e2ee_check_stream_privacy_code
            } else {
                R.string.e2ee_check_call_privacy_code
            },
            icon = R.drawable.ic_lock_24dp,
        ) {
            val code = viewState.privacyCode ?: return@bind
            dismiss()
            WidgetVoicePrivacyCodeBottomSheet.show(parentFragmentManager, code, isStream)
        }

        binding.voiceSettingsNoiseGroup.root.visibility = if (isMenu) View.GONE else View.VISIBLE
        bindNoiseOptions(viewState.noiseProcessing)

        binding.voiceSettingsMembersTitle.visibility = if (isMenu) View.VISIBLE else View.GONE
        binding.voiceSettingsMembersGroup.visibility = if (isMenu) View.VISIBLE else View.GONE
        binding.voiceSettingsMembersTitle.text = resources.getQuantityString(
            R.plurals.voice_panel_member_count_plural,
            viewState.members.size,
            viewState.members.size,
        )
        binding.voiceSettingsInvite.root.visibility = if (viewState.showInviteItem) View.VISIBLE else View.GONE
        binding.voiceSettingsInvite.bind(
            label = R.string.invite_a_friend,
            icon = R.drawable.ic_header_members_add_white_24dp,
        ) {
            dismiss()
            launchChannelInvite(this, channel, INVITE_ANALYTICS_SOURCE)
        }
        bindMembers(viewState.members, viewState.showInviteItem)
    }

    private fun memberSummary(members: List<VoiceMember>): String? = when (members.size) {
        0 -> null
        1 -> members[0].name
        2 -> getString(R.string.voice_panel_overview_header_2_people, members[0].name, members[1].name)
        else -> resources.getQuantityString(
            R.plurals.voice_panel_overview_header_3_or_more_people,
            members.size - 2,
            members[0].name,
            members[1].name,
            members.size - 2,
        )
    }

    private fun bindNoiseOptions(selected: StoreMediaSettings.NoiseProcessing) {
        val options = listOf(
            Triple(binding.voiceSettingsNoiseGroup.voiceNoiseKrisp, R.string.user_settings_noise_cancellation_krisp, StoreMediaSettings.NoiseProcessing.Cancellation),
            Triple(binding.voiceSettingsNoiseGroup.voiceNoiseStandard, R.string.user_settings_noise_cancellation_standard, StoreMediaSettings.NoiseProcessing.Suppression),
            Triple(binding.voiceSettingsNoiseGroup.voiceNoiseNone, R.string.user_settings_noise_cancellation_disabled, StoreMediaSettings.NoiseProcessing.None),
        )
        options.forEach { (row, label, value) ->
            row.voiceRadioLabel.setText(label)
            row.voiceRadioButton.isChecked = selected == value
            row.root.setOnClickListener { viewModel.onSelectNoiseProcessing(value) }
        }
    }

    /** The router needs the hosting activity, and the sheet has to be gone before it swaps screens. */
    private fun navigate(launch: (android.content.Context) -> Unit) {
        val activity = requireActivity()
        dismiss()
        launch(activity)
    }

    private fun bindMembers(members: List<VoiceMember>, hasInviteRow: Boolean) {
        val container = binding.voiceSettingsMembers
        container.removeAllViews()
        members.forEachIndexed { index, member ->
            if (index > 0 || hasInviteRow) {
                container.addView(layoutInflater.inflate(R.layout.view_voice_settings_divider, container, false))
            }
            val row = ViewVoiceSettingsMemberRowBinding.inflate(layoutInflater, container, false)
            row.voiceMemberAvatar.setImageURI(IconUtils.getForUser(member.user, size = 64))
            row.voiceMemberName.text = member.name
            row.voiceMemberVideo.visibility = if (member.hasVideo) View.VISIBLE else View.GONE
            row.voiceMemberMute.visibility = if (member.isMuted) View.VISIBLE else View.GONE
            container.addView(row.root)
        }
    }

    private fun ViewVoiceSettingsRowBinding.bind(
        @StringRes label: Int,
        @StringRes sublabel: Int? = null,
        @DrawableRes icon: Int? = null,
        onClick: () -> Unit,
    ) {
        voiceRowLabel.setText(label)
        voiceRowSublabel.setTextAndVisibilityBy(sublabel?.let(::getString))
        voiceRowIcon.visibility = if (icon == null) View.GONE else View.VISIBLE
        icon?.let(voiceRowIcon::setImageResource)
        root.setOnClickListener { onClick() }
    }

    private fun ViewVoiceSettingsSwitchRowBinding.bindSwitch(
        @StringRes label: Int,
        @StringRes sublabel: Int? = null,
        @DrawableRes icon: Int? = null,
        checked: Boolean,
        onClick: () -> Unit,
    ) {
        voiceRowLabel.setText(label)
        voiceRowSublabel.setTextAndVisibilityBy(sublabel?.let(::getString))
        voiceRowIcon.visibility = if (icon == null) View.GONE else View.VISIBLE
        icon?.let(voiceRowIcon::setImageResource)
        voiceRowSwitch.isChecked = checked
        root.setOnClickListener { onClick() }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_MODE = "com.discord.intent.extra.EXTRA_VOICE_SETTINGS_MODE"
        private const val INVITE_ANALYTICS_SOURCE = "Voice Call"

        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            mode: VoiceSettingsMode = VoiceSettingsMode.MENU,
        ) {
            WidgetVoiceSettingsBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_CHANNEL_ID, channelId)
                    putInt(EXTRA_MODE, mode.ordinal)
                }
            }.show(fragmentManager, WidgetVoiceSettingsBottomSheet::class.java.simpleName)
        }
    }
}
