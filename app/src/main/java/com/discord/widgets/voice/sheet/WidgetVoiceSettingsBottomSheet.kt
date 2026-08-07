package com.discord.widgets.voice.sheet

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.guild.GuildFeature
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.databinding.WidgetVoiceSettingsBottomSheetBinding
import com.discord.models.guild.Guild
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.channels.settings.WidgetChannelNotificationSettings
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

        binding.voiceSettingsInvite.visibility = if (viewState.showInviteItem) View.VISIBLE else View.GONE
        binding.voiceSettingsInvite.setOnClickListener {
            dismiss()
            launchChannelInvite(this, channel, INVITE_ANALYTICS_SOURCE)
        }

        binding.voiceSettingsToggleVideoParticipants.isChecked = viewState.showVoiceParticipants
        binding.voiceSettingsToggleVideoParticipants.visibility =
            if (viewState.showVoiceParticipantsToggle) View.VISIBLE else View.GONE
        binding.voiceSettingsToggleVideoParticipants.setOnClickListener {
            viewModel.onToggleVoiceParticipantsHidden()
        }

        binding.voiceSettingsNoiseSuppression.isChecked = viewState.noiseCancellationEnabled
        binding.voiceSettingsNoiseSuppression.setOnClickListener { viewModel.onToggleNoiseCancellation() }

        binding.voiceSettingsStageSettings.visibility = if (viewState.showStageSettings) View.VISIBLE else View.GONE
        binding.voiceSettingsStageSettings.setOnClickListener {
            dismiss()
            WidgetStageStartEventBottomSheet.show(parentFragmentManager, channelId)
        }

        // Scheduled events belong to vocal channels only.
        binding.voiceSettingsEvents.visibility = if (channel.isGuildVocalChannel()) View.VISIBLE else View.GONE
        binding.voiceSettingsEvents.setOnClickListener {
            dismiss()
            WidgetGuildScheduledEventListBottomSheet.show(
                parentFragmentManager,
                channel.guildId,
                channel.id,
            )
        }

        binding.voiceSettingsVoiceSettings.setOnClickListener {
            dismiss()
            WidgetSettingsVoice.launch(requireContext())
        }

        // NOTE: the reference's visibility ternary for this row is one of the branches JADX
        // decompiles as `... ? false : false ? 0 : 8`, and Vineflower cannot decompile this class
        // at all, so the condition below is inferred from the operands JADX did recover
        // (stage channel AND a COMMUNITY guild) and is NOT confirmed.
        val guildIsCommunity = viewState.guild?.hasFeature(GuildFeature.COMMUNITY) == true
        binding.voiceSettingsNotifications.visibility =
            if (channel.isStageVoiceChannel() && guildIsCommunity) View.VISIBLE else View.GONE
        binding.voiceSettingsNotifications.setOnClickListener {
            WidgetChannelNotificationSettings.launch(requireContext(), channelId)
        }

        binding.voiceSettingsReport.visibility = if (viewState.showReportItem) View.VISIBLE else View.GONE
        binding.voiceSettingsReport.setOnClickListener {
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
