package com.discord.widgets.settings

import android.graphics.Typeface
import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
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

    private val viewModel: MuteSettingsSheetViewModel by appViewModels {
        MuteSettingsSheetViewModel(
            guildId = argumentsOrDefault.getLong(ARG_GUILD_ID, 0L),
            channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID, 0L),
        )
    }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var unmuteButton: LinearLayout
    private lateinit var unmuteButtonLabel: TextView
    private lateinit var unmuteButtonDetailsLabel: TextView
    private lateinit var muteOptions: LinearLayout
    private lateinit var notificationSettingsButtonContainer: FrameLayout
    private lateinit var notificationSettingsButton: LinearLayout
    private lateinit var notificationSettingsLabel: TextView
    private lateinit var notificationSettingsOverridesLabel: TextView
    private lateinit var channelMutedDetails: TextView

    override fun getContentViewResId(): Int = R.layout.widget_mute_settings_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        title = view.findViewById(R.id.title)
        subtitle = view.findViewById(R.id.subtitle)
        unmuteButton = view.findViewById(R.id.unmute_button)
        unmuteButtonLabel = view.findViewById(R.id.unmute_button_label)
        unmuteButtonDetailsLabel = view.findViewById(R.id.unmute_button_details_label)
        muteOptions = view.findViewById(R.id.mute_settings_sheet_mute_options)
        notificationSettingsButtonContainer = view.findViewById(R.id.notification_settings_button_container)
        notificationSettingsButton = view.findViewById(R.id.notification_settings_button)
        notificationSettingsLabel = view.findViewById(R.id.notification_settings_label)
        notificationSettingsOverridesLabel = view.findViewById(R.id.notification_settings_overrides_label)
        channelMutedDetails = view.findViewById(R.id.channel_muted_details)

        bindDuration(R.id.option_fifteen_minutes, FIFTEEN_MINUTES_MS)
        bindDuration(R.id.option_one_hour, ONE_HOUR_MS)
        bindDuration(R.id.option_eight_hours, EIGHT_HOURS_MS)
        bindDuration(R.id.option_twenty_four_hours, TWENTY_FOUR_HOURS_MS)
        bindDuration(R.id.option_always, MuteSettingsSheetViewModel.MUTE_DURATION_ALWAYS)

        unmuteButton.setOnClickListener { viewModel.unmute(it.context.applicationContext) }
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState().distinctUntilChanged().ui(this).subscribe { updateViews(it) }
        viewModel.observeEvents().ui(this).subscribe { handleEvent(it) }
    }

    private fun bindDuration(rowId: Int, durationMs: Long) {
        requireView().findViewById<TextView>(rowId).setOnClickListener {
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
                title.text = titleResFor(viewState.settingsType)?.let { getString(it) }.orEmpty()
                subtitle.text = viewState.subtitle
                configureUnmuteButton(viewState)
                configureNotificationSettings(viewState)
            }
        }
    }

    private fun configureUnmuteButton(viewState: MuteSettingsSheetViewModel.ViewState.Loaded) {
        if (!viewState.isChannelMuted) {
            unmuteButton.visibility = View.GONE
            muteOptions.visibility = View.VISIBLE
            return
        }

        val emphasis = mapOf(
            HOOK_END_TIME to listOf(
                StyleSpan(Typeface.BOLD),
                ForegroundColorSpan(ThemedColor.resolve(requireContext(), R.attr.colorHeaderPrimary)),
            ),
        )
        unmuteButtonLabel.text = SettingsStrings.render(
            requireContext(),
            R.string.unmute_channel,
            "name" to viewState.subtitle,
            hooks = emphasis,
        )

        val isConversation =
            viewState.settingsType == SettingsType.DM || viewState.settingsType == SettingsType.GROUP_DM
        val endTime = viewState.muteEndTime
        unmuteButtonDetailsLabel.text = when {
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

        unmuteButton.visibility = View.VISIBLE
        muteOptions.visibility = View.GONE
    }

    private fun configureNotificationSettings(viewState: MuteSettingsSheetViewModel.ViewState.Loaded) {
        notificationSettingsOverridesLabel.text = when (viewState.notificationSetting) {
            ModelNotificationSettings.FREQUENCY_ALL -> getString(R.string.form_label_all_messages_short)
            ModelNotificationSettings.FREQUENCY_MENTIONS -> getString(R.string.form_label_only_mentions_short)
            ModelNotificationSettings.FREQUENCY_NOTHING -> getString(R.string.form_label_nothing)
            else -> ""
        }

        // A guild, a DM, a group DM and a category have no per-channel override screen to reach.
        if (viewState.settingsType in TYPES_WITHOUT_OVERRIDES) {
            notificationSettingsButtonContainer.visibility = View.GONE
            channelMutedDetails.visibility = View.GONE
            return
        }

        if (viewState.isChannelMuted || viewState.isGuildMuted) {
            notificationSettingsLabel.alpha = INACTIVE_OPACITY
            notificationSettingsButton.setOnClickListener(null)
            channelMutedDetails.text = if (viewState.isChannelMuted) {
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
            channelMutedDetails.visibility = View.VISIBLE
        } else {
            notificationSettingsLabel.alpha = ACTIVE_OPACITY
            notificationSettingsButton.setOnClickListener { viewModel.onChannelSettingsSelected() }
            channelMutedDetails.visibility = View.GONE
        }
        notificationSettingsButtonContainer.visibility = View.VISIBLE
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
