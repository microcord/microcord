package com.discord.widgets.voice.sheet

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.guild.GuildFeature
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.models.guild.Guild
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.channels.settings.WidgetChannelNotificationSettings
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventListBottomSheet
import com.discord.widgets.mobile_reports.WidgetMobileReports
import com.discord.widgets.settings.WidgetSettingsVoice
import com.discord.widgets.stage.sheet.WidgetStageStartEventBottomSheet
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.switchmaterial.SwitchMaterial

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
        val showInviteItem: Boolean
        val noiseCancellationEnabled: Boolean
        val showStageSettings: Boolean
        val showReportItem: Boolean
        val guild: Guild?
    }

    private val channelId: Long by lazy { argumentsOrDefault.getLong(EXTRA_CHANNEL_ID) }

    private val viewModel: WidgetVoiceSettingsBottomSheetViewModel by appViewModels {
        WidgetVoiceSettingsBottomSheetViewModel(channelId)
    }

    private lateinit var toggleVideoParticipants: SwitchMaterial
    private lateinit var noiseSuppression: SwitchMaterial
    private lateinit var invite: TextView
    private lateinit var stageSettings: TextView
    private lateinit var voiceSettings: TextView
    private lateinit var notifications: TextView
    private lateinit var events: TextView
    private lateinit var report: TextView

    override fun getContentViewResId(): Int = R.layout.widget_voice_settings_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toggleVideoParticipants = view.findViewById(R.id.voice_settings_toggle_video_participants)
        noiseSuppression = view.findViewById(R.id.voice_settings_noise_suppression)
        invite = view.findViewById(R.id.voice_settings_invite)
        stageSettings = view.findViewById(R.id.voice_settings_stage_settings)
        voiceSettings = view.findViewById(R.id.voice_settings_voice_settings)
        notifications = view.findViewById(R.id.voice_settings_notifications)
        events = view.findViewById(R.id.voice_settings_events)
        report = view.findViewById(R.id.voice_settings_report)
        setBottomSheetState(BottomSheetBehavior.STATE_EXPANDED)
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .subscribe(::configureUI)
    }

    private fun configureUI(viewState: ViewState) {
        val channel = viewState.channel

        invite.visibility = if (viewState.showInviteItem) View.VISIBLE else View.GONE
        invite.setOnClickListener {
            dismiss()
            launchChannelInvite(this, channel, INVITE_ANALYTICS_SOURCE)
        }

        toggleVideoParticipants.isChecked = viewState.showVoiceParticipants
        toggleVideoParticipants.visibility =
            if (viewState.showVoiceParticipantsToggle) View.VISIBLE else View.GONE
        toggleVideoParticipants.setOnClickListener {
            viewModel.onToggleVoiceParticipantsHidden()
        }

        noiseSuppression.isChecked = viewState.noiseCancellationEnabled
        noiseSuppression.setOnClickListener { viewModel.onToggleNoiseCancellation() }

        stageSettings.visibility = if (viewState.showStageSettings) View.VISIBLE else View.GONE
        stageSettings.setOnClickListener {
            dismiss()
            WidgetStageStartEventBottomSheet.show(parentFragmentManager, channelId)
        }

        // Scheduled events belong to vocal channels only.
        events.visibility = if (channel.isGuildVocalChannel()) View.VISIBLE else View.GONE
        events.setOnClickListener {
            dismiss()
            WidgetGuildScheduledEventListBottomSheet.show(
                parentFragmentManager,
                channel.guildId,
                channel.id,
            )
        }

        voiceSettings.setOnClickListener {
            dismiss()
            WidgetSettingsVoice.launch(requireContext())
        }

        // NOTE: the reference's visibility ternary for this row is one of the branches JADX
        // decompiles as `... ? false : false ? 0 : 8`, and Vineflower cannot decompile this class
        // at all, so the condition below is inferred from the operands JADX did recover
        // (stage channel AND a COMMUNITY guild) and is NOT confirmed.
        val guildIsCommunity = viewState.guild?.hasFeature(GuildFeature.COMMUNITY) == true
        notifications.visibility =
            if (channel.isStageVoiceChannel() && guildIsCommunity) View.VISIBLE else View.GONE
        notifications.setOnClickListener {
            WidgetChannelNotificationSettings.launch(requireContext(), channelId)
        }

        report.visibility = if (viewState.showReportItem) View.VISIBLE else View.GONE
        report.setOnClickListener {
            WidgetMobileReports.launchStageChannelReport(requireContext(), channel.id)
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val INVITE_ANALYTICS_SOURCE = "Voice Call"

        fun show(fragmentManager: FragmentManager, channelId: Long) {
            WidgetVoiceSettingsBottomSheet().apply {
                arguments = Bundle().apply { putLong(EXTRA_CHANNEL_ID, channelId) }
                show(fragmentManager, WidgetVoiceSettingsBottomSheet::class.java.name)
            }
        }
    }
}
