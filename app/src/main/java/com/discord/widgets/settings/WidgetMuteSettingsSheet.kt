package com.discord.widgets.settings

import android.graphics.Typeface
import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.databinding.WidgetMuteSettingsSheetBinding
import com.discord.models.domain.ModelNotificationSettings
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.discord.views.ThemedColor
import com.discord.widgets.channels.settings.WidgetChannelNotificationSettings
import com.discord.widgets.settings.MuteSettingsSheetViewModel.SettingsType
import java.text.DateFormat
import rx.subscriptions.CompositeSubscription

/**
 * The mute-duration picker: 15 minutes, 1 hour, 8 hours, 24 hours, or until the user turns it back
 * on. Shown for a guild by [showForGuild] and for a channel, category, thread or DM by
 * [showForChannel].
 *
 * Reference: `com.discord.widgets.settings.WidgetMuteSettingsSheet`. All state comes from
 * [MuteSettingsSheetViewModel]; the sheet only renders it and forwards taps.
 *
 * Once the surface is already muted the duration list is swapped for a single unmute row, and — for
 * a channel or thread — a "Notification Settings" row leads to the per-channel overrides. That row
 * is inert while the channel or its server is muted, and says which of the two is responsible.
 */
class WidgetMuteSettingsSheet : AppBottomSheet() {

    private var _binding: WidgetMuteSettingsSheetBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val viewModel: MuteSettingsSheetViewModel by appViewModels {
        MuteSettingsSheetViewModel(
            guildId = argumentsOrDefault.getLong(ARG_GUILD_ID, 0L),
            channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID, 0L),
        )
    }

    override fun getContentViewResId(): Int = R.layout.widget_mute_settings_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetMuteSettingsSheetBinding.bind(view)

        bindDuration(binding.optionFifteenMinutes, FIFTEEN_MINUTES_MS)
        bindDuration(binding.optionOneHour, ONE_HOUR_MS)
        bindDuration(binding.optionEightHours, EIGHT_HOURS_MS)
        bindDuration(binding.optionTwentyFourHours, TWENTY_FOUR_HOURS_MS)
        bindDuration(binding.optionAlways, MuteSettingsSheetViewModel.MUTE_DURATION_ALWAYS)

        binding.unmuteButton.setOnClickListener { viewModel.unmute(it.context.applicationContext) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState().distinctUntilChanged().ui(this).subscribe { updateViews(it) }
        viewModel.observeEvents().ui(this).subscribe { handleEvent(it) }
    }

    private fun bindDuration(row: TextView, durationMs: Long) {
        row.setOnClickListener {
            viewModel.selectMuteDurationMs(durationMs, it.context.applicationContext)
        }
    }

    private fun handleEvent(event: MuteSettingsSheetViewModel.Event) = when (event) {
        is MuteSettingsSheetViewModel.Event.Dismiss -> dismiss()
        is MuteSettingsSheetViewModel.Event.NavigateToChannelSettings -> {
            SettingsNavigation.launch(
                requireContext(),
                WidgetChannelNotificationSettings.newInstance(event.channelId),
            )
            dismiss()
        }
    }

    private fun updateViews(viewState: MuteSettingsSheetViewModel.ViewState) {
        if (_binding == null) return
        when (viewState) {
            is MuteSettingsSheetViewModel.ViewState.Uninitialized -> Unit
            is MuteSettingsSheetViewModel.ViewState.Failure -> {
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.default_failure_to_perform_action_message,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                dismiss()
            }

            is MuteSettingsSheetViewModel.ViewState.Loaded -> {
                binding.title.text = titleResFor(viewState.settingsType)?.let { getString(it) }.orEmpty()
                binding.subtitle.text = viewState.subtitle
                configureUnmuteButton(viewState)
                configureNotificationSettings(viewState)
            }
        }
    }

    private fun configureUnmuteButton(viewState: MuteSettingsSheetViewModel.ViewState.Loaded) {
        if (!viewState.isChannelMuted) {
            binding.unmuteButton.visibility = View.GONE
            binding.muteSettingsSheetMuteOptions.visibility = View.VISIBLE
            return
        }

        val emphasis = mapOf(
            HOOK_END_TIME to listOf(
                StyleSpan(Typeface.BOLD),
                ForegroundColorSpan(ThemedColor.resolve(requireContext(), R.attr.colorHeaderPrimary)),
            ),
        )
        binding.unmuteButtonLabel.text = SettingsStrings.render(
            requireContext(),
            R.string.unmute_channel,
            "name" to viewState.subtitle,
            hooks = emphasis,
        )

        val isConversation =
            viewState.settingsType == SettingsType.DM || viewState.settingsType == SettingsType.GROUP_DM
        val endTime = viewState.muteEndTime
        binding.unmuteButtonDetailsLabel.text = when {
            endTime == null && isConversation -> getString(R.string.form_label_mobile_dm_muted)
            endTime == null -> getString(R.string.form_label_mobile_channel_muted)
            else -> SettingsStrings.render(
                requireContext(),
                if (isConversation) {
                    R.string.form_label_mobile_dm_muted_until
                } else {
                    R.string.form_label_mobile_channel_muted_until
                },
                "endTime" to renderMuteEndTime(endTime),
                hooks = emphasis,
            )
        }

        binding.unmuteButton.visibility = View.VISIBLE
        binding.muteSettingsSheetMuteOptions.visibility = View.GONE
    }

    private fun configureNotificationSettings(viewState: MuteSettingsSheetViewModel.ViewState.Loaded) {
        binding.notificationSettingsOverridesLabel.text = when (viewState.notificationSetting) {
            ModelNotificationSettings.FREQUENCY_ALL -> getString(R.string.form_label_all_messages_short)
            ModelNotificationSettings.FREQUENCY_MENTIONS -> getString(R.string.form_label_only_mentions_short)
            ModelNotificationSettings.FREQUENCY_NOTHING -> getString(R.string.form_label_nothing)
            else -> ""
        }

        // A guild, a DM, a group DM and a category have no per-channel override screen to reach.
        if (viewState.settingsType in TYPES_WITHOUT_OVERRIDES) {
            binding.notificationSettingsButtonContainer.visibility = View.GONE
            binding.channelMutedDetails.visibility = View.GONE
            return
        }

        if (viewState.isChannelMuted || viewState.isGuildMuted) {
            binding.notificationSettingsLabel.alpha = INACTIVE_OPACITY
            binding.notificationSettingsButton.setOnClickListener(null)
            binding.channelMutedDetails.text = if (viewState.isChannelMuted) {
                getString(R.string.form_description_mobile_notification_muted)
            } else {
                SettingsStrings.render(
                    requireContext(),
                    R.string.form_label_mobile_channel_override_guild_muted,
                    hooks = mapOf(
                        HOOK_MUTED to listOf(
                            ForegroundColorSpan(
                                ContextCompat.getColor(requireContext(), R.color.status_red_500),
                            ),
                        ),
                    ),
                )
            }
            binding.channelMutedDetails.visibility = View.VISIBLE
        } else {
            binding.notificationSettingsLabel.alpha = ACTIVE_OPACITY
            binding.notificationSettingsButton.setOnClickListener { viewModel.onChannelSettingsSelected() }
            binding.channelMutedDetails.visibility = View.GONE
        }
        binding.notificationSettingsButtonContainer.visibility = View.VISIBLE
    }

    private fun renderMuteEndTime(muteEndTime: String): String = TimeUtils.renderUtcDateTime(
        utcDateTime = muteEndTime,
        context = requireContext(),
        outputDateFormat = DateFormat.SHORT,
        outputTimeFormat = DateFormat.SHORT,
    )

    companion object {
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"
        private const val ARG_CHANNEL_ID = "ARG_CHANNEL_ID"

        private const val ACTIVE_OPACITY = 1.0f
        private const val INACTIVE_OPACITY = 0.2f

        private const val FIFTEEN_MINUTES_MS = 900_000L
        private const val ONE_HOUR_MS = 3_600_000L
        private const val EIGHT_HOURS_MS = 28_800_000L
        private const val TWENTY_FOUR_HOURS_MS = 86_400_000L

        /** Hook names used by the recovered mute strings. */
        private const val HOOK_END_TIME = "endTimeHook"
        private const val HOOK_MUTED = "mutedHook"

        private val TYPES_WITHOUT_OVERRIDES = setOf(
            SettingsType.GUILD,
            SettingsType.DM,
            SettingsType.GROUP_DM,
            SettingsType.CATEGORY,
        )

        @StringRes
        internal fun titleResFor(settingsType: SettingsType): Int? = when (settingsType) {
            SettingsType.GUILD -> R.string.mute_settings_mute_server
            SettingsType.DM, SettingsType.GROUP_DM -> R.string.mute_settings_mute_this_conversation
            SettingsType.THREAD -> R.string.mute_settings_mute_thread
            SettingsType.GUILD_CHANNEL -> R.string.mute_settings_mute_channel
            SettingsType.CATEGORY -> R.string.mute_settings_mute_category
            SettingsType.UNKNOWN -> null
        }

        fun showForGuild(guildId: Long, fragmentManager: FragmentManager) {
            show(fragmentManager, Bundle().apply { putLong(ARG_GUILD_ID, guildId) })
        }

        fun showForChannel(channelId: Long, fragmentManager: FragmentManager) {
            show(fragmentManager, Bundle().apply { putLong(ARG_CHANNEL_ID, channelId) })
        }

        private fun show(fragmentManager: FragmentManager, arguments: Bundle) {
            WidgetMuteSettingsSheet()
                .apply { this.arguments = arguments }
                .show(fragmentManager, WidgetMuteSettingsSheet::class.java.name)
        }
    }
}
