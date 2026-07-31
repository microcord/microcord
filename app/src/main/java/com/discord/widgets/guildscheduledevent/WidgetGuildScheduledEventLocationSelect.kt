package com.discord.widgets.guildscheduledevent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.i18n.I18nHooks
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.views.CheckedSetting
import com.discord.widgets.channels.WidgetChannelSelector
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class WidgetGuildScheduledEventLocationSelect : AppFragment(R.layout.widget_guild_scheduled_event_location_select) {

    private val guildId: Long get() = requireArguments().getLong(EXTRA_GUILD_ID)
    private val initialChannelId: Long?
        get() = requireArguments().takeIf { it.containsKey(EXTRA_CHANNEL_ID) }?.getLong(EXTRA_CHANNEL_ID)
    private val existingEventId: Long?
        get() = requireArguments().takeIf { it.containsKey(EXTRA_EVENT_ID) }?.getLong(EXTRA_EVENT_ID)
    private val viewModel: WidgetGuildScheduledEventLocationSelectViewModel by appViewModels {
        WidgetGuildScheduledEventLocationSelectViewModel(guildId, initialChannelId, existingEventId)
    }

    private lateinit var stageOption: CheckedSetting
    private lateinit var voiceOption: CheckedSetting
    private lateinit var externalOption: CheckedSetting
    private lateinit var channelInput: TextInputEditText
    private lateinit var channelLayout: TextInputLayout
    private lateinit var externalInput: TextInputEditText
    private lateinit var externalLayout: TextInputLayout
    private lateinit var locationHeader: TextView
    private lateinit var nextButton: MaterialButton
    private var currentState: WidgetGuildScheduledEventLocationSelectViewModel.ViewState.Valid? = null

    private val settingsLauncher = WidgetGuildScheduledEventSettings.registerForResult(
        this,
        onFinished = {
            activity?.setResult(Activity.RESULT_OK)
            activity?.finish()
        },
        onQuit = {
            activity?.setResult(RESULT_QUIT)
            activity?.finish()
        },
    )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        stageOption = view.findViewById(R.id.stage_channel_option)
        voiceOption = view.findViewById(R.id.voice_channel_option)
        externalOption = view.findViewById(R.id.external_option)
        channelInput = view.findViewById(R.id.channel_location_input)
        channelLayout = view.findViewById(R.id.channel_location_layout)
        externalInput = view.findViewById(R.id.external_location_input)
        externalLayout = view.findViewById(R.id.external_location_layout)
        locationHeader = view.findViewById(R.id.location_header)
        nextButton = view.findViewById(R.id.next_button)

        view.findViewById<TextView>(R.id.step_text).text = I18nHooks.format(
            requireContext(), R.string.guild_event_step_label, mapOf("step" to 1, "total" to 3),
        )
        view.findViewById<View>(R.id.close_button).setOnClickListener { activity?.finish() }
        externalInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(value: Editable?) = viewModel.setExternalLocation(value?.toString().orEmpty())
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        channelInput.setOnClickListener { showChannelChooser() }
        WidgetChannelSelector.registerForResult(this, CHANNEL_SELECT_REQUEST_KEY) { channelId, _ ->
            viewModel.selectChannel(channelId)
        }
        externalLayout.setOnImeActionDone { navigateNext() }
        nextButton.setOnClickListener { navigateNext() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildScheduledEventLocationSelect::class.java) { configureUi(it) }
    }

    private fun configureUi(state: WidgetGuildScheduledEventLocationSelectViewModel.ViewState) {
        if (state is WidgetGuildScheduledEventLocationSelectViewModel.ViewState.Invalid) {
            activity?.finish()
            return
        }
        state as WidgetGuildScheduledEventLocationSelectViewModel.ViewState.Valid
        currentState = state
        configureOption(stageOption, state, GuildScheduledEventEntityType.STAGE_INSTANCE, state.availableStageChannelIds)
        configureOption(voiceOption, state, GuildScheduledEventEntityType.VOICE, state.availableVoiceChannelIds)
        configureOption(
            externalOption,
            state,
            GuildScheduledEventEntityType.EXTERNAL,
            if (state.canCreateExternalEvent) setOf(1L) else emptySet(),
        )
        val showStage = state.availableStageChannelIds.isNotEmpty() || state.showStageOptionIfUnavailable
        stageOption.visibility = if (showStage) View.VISIBLE else View.GONE
        requireView().findViewById<View>(R.id.voice_channel_divider).visibility = stageOption.visibility

        val channelSelected = state.selectedLocationOption == GuildScheduledEventEntityType.STAGE_INSTANCE ||
            state.selectedLocationOption == GuildScheduledEventEntityType.VOICE
        locationHeader.visibility = if (state.selectedLocationOption == GuildScheduledEventEntityType.NONE) View.GONE else View.VISIBLE
        channelLayout.visibility = if (channelSelected) View.VISIBLE else View.GONE
        externalLayout.visibility = if (state.selectedLocationOption == GuildScheduledEventEntityType.EXTERNAL) View.VISIBLE else View.GONE
        locationHeader.setText(
            if (state.selectedLocationOption == GuildScheduledEventEntityType.EXTERNAL) {
                R.string.guild_event_location_elsewhere_field_label
            } else {
                R.string.guild_event_location_voice_field_label
            },
        )
        channelInput.isEnabled = state.canChangeChannel
        channelInput.alpha = if (state.canChangeChannel) 1f else 0.5f
        if (channelInput.text?.toString() != state.selectedChannel?.name) channelInput.setText(state.selectedChannel?.name)
        if (externalInput.text?.toString() != state.externalLocation) externalInput.setText(state.externalLocation)
        nextButton.isEnabled = isNextEnabled(state)
    }

    private fun configureOption(
        setting: CheckedSetting,
        state: WidgetGuildScheduledEventLocationSelectViewModel.ViewState.Valid,
        value: GuildScheduledEventEntityType,
        availableIds: Set<Long>,
    ) {
        setting.setChecked(state.selectedLocationOption == value, false)
        val disabledText = when {
            !state.canChangeChannel -> getText(R.string.guild_event_location_disabled_cannot_edit)
            availableIds.isEmpty() && value == GuildScheduledEventEntityType.EXTERNAL ->
                getText(R.string.guild_event_location_disabled_external_no_permission)
            availableIds.isEmpty() -> getText(R.string.guild_event_location_disabled_no_available_channel)
            else -> null
        }
        setting.setDisabledTooltip(disabledText)
        if (disabledText == null) setting.setContainerClickListener { viewModel.selectLocationOption(value) }
    }

    private fun showChannelChooser() {
        val state = currentState ?: return
        if (!state.canChangeChannel) return
        val ids = when (state.selectedLocationOption) {
            GuildScheduledEventEntityType.STAGE_INSTANCE -> state.availableStageChannelIds
            GuildScheduledEventEntityType.VOICE -> state.availableVoiceChannelIds
            else -> emptySet()
        }
        if (ids.isEmpty()) return
        WidgetChannelSelector.launch(
            this,
            guildId,
            CHANNEL_SELECT_REQUEST_KEY,
            includeNoChannel = false,
            noChannelStringId = R.string.none,
            filterFunction = WidgetChannelSelector.SetFilterFunction(ids),
        )
    }

    private fun navigateNext() {
        val state = currentState ?: return
        WidgetGuildScheduledEventSettings.launch(
            requireContext(),
            settingsLauncher,
            guildId,
            state.selectedLocationOption,
            state.selectedChannel?.id,
            state.externalLocation,
            existingEventId,
        )
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_EVENT_ID = "com.discord.intent.extra.EXTRA_GUILD_SCHEDULED_EVENT_ID"
        private const val CHANNEL_SELECT_REQUEST_KEY = "GUILD_SCHEDULED_EVENT_LOCATION_SELECT_REQUEST_KEY"
        private const val RESULT_QUIT = 2

        internal fun isNextEnabled(
            state: WidgetGuildScheduledEventLocationSelectViewModel.ViewState.Valid,
        ): Boolean = when (state.selectedLocationOption) {
            GuildScheduledEventEntityType.STAGE_INSTANCE, GuildScheduledEventEntityType.VOICE ->
                state.selectedChannel != null
            GuildScheduledEventEntityType.EXTERNAL -> state.externalLocation.isNotEmpty()
            else -> false
        }

        @JvmStatic
        @JvmOverloads
        fun launch(context: Context, guildId: Long, channelId: Long? = null) {
            val extras = Intent().putExtra(EXTRA_GUILD_ID, guildId).apply {
                channelId?.let { putExtra(EXTRA_CHANNEL_ID, it) }
            }
            AppScreenRouter.launch(context, WidgetGuildScheduledEventLocationSelect::class.java, extras)
        }

        @JvmStatic
        fun launchForEdit(context: Context, guildId: Long, eventId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetGuildScheduledEventLocationSelect::class.java,
                Intent().putExtra(EXTRA_GUILD_ID, guildId).putExtra(EXTRA_EVENT_ID, eventId),
            )
        }
    }
}
