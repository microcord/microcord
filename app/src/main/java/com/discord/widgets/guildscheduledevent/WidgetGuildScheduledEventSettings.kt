package com.discord.widgets.guildscheduledevent

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.i18n.I18nHooks
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar

class WidgetGuildScheduledEventSettings : AppFragment(R.layout.widget_guild_scheduled_event_settings) {

    private val guildId: Long get() = requireArguments().getLong(EXTRA_GUILD_ID)
    private val channelId: Long?
        get() = requireArguments().takeIf { it.containsKey(EXTRA_CHANNEL_ID) }?.getLong(EXTRA_CHANNEL_ID)
    private val existingEventId: Long?
        get() = requireArguments().takeIf { it.containsKey(EXTRA_EVENT_ID) }?.getLong(EXTRA_EVENT_ID)
    private val entityType: GuildScheduledEventEntityType
        get() = requireNotNull(requireArguments().getSerializable(EXTRA_ENTITY_TYPE) as? GuildScheduledEventEntityType)
    private val externalLocation: String? get() = requireArguments().getString(EXTRA_EXTERNAL_LOCATION)
    private val viewModel: GuildScheduledEventSettingsViewModel by appViewModels {
        GuildScheduledEventSettingsViewModel(
            guildId, existingEventId, entityType, channelId, externalLocation,
        )
    }

    private lateinit var topic: TextInputEditText
    private lateinit var description: TextInputEditText
    private lateinit var startDate: TextInputEditText
    private lateinit var startTime: TextInputEditText
    private lateinit var endDate: TextInputEditText
    private lateinit var endTime: TextInputEditText
    private lateinit var endDateHeader: TextView
    private lateinit var endTimeHeader: TextView
    private lateinit var endDateLayout: TextInputLayout
    private lateinit var endTimeLayout: TextInputLayout
    private lateinit var next: MaterialButton
    private var currentState: GuildScheduledEventSettingsViewModel.ViewState.Initialized? = null

    private val previewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK || result.resultCode == RESULT_QUIT) {
            activity?.setResult(result.resultCode, result.data)
            activity?.finish()
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        topic = view.findViewById(R.id.guild_scheduled_event_settings_topic_input)
        description = view.findViewById(R.id.guild_scheduled_event_settings_description_input)
        startDate = view.findViewById(R.id.guild_scheduled_event_settings_start_date_input)
        startTime = view.findViewById(R.id.guild_scheduled_event_settings_start_time_input)
        endDate = view.findViewById(R.id.end_date_input)
        endTime = view.findViewById(R.id.end_time_input)
        endDateHeader = view.findViewById(R.id.end_date_header)
        endTimeHeader = view.findViewById(R.id.end_time_header)
        endDateLayout = view.findViewById(R.id.end_date_layout)
        endTimeLayout = view.findViewById(R.id.end_time_layout)
        next = view.findViewById(R.id.guild_scheduled_event_settings_next_button)

        view.findViewById<TextView>(R.id.guild_scheduled_event_settings_step_text).text = I18nHooks.format(
            requireContext(), R.string.guild_event_step_label, mapOf("step" to 2, "total" to 3),
        )
        view.findViewById<View>(R.id.back_button).setOnClickListener { activity?.finish() }
        view.findViewById<View>(R.id.close_button).setOnClickListener {
            activity?.setResult(RESULT_QUIT)
            activity?.finish()
        }
        topic.watch(viewModel::setTopic)
        description.watch(viewModel::setDescription)
        startDate.setOnClickListener { currentState?.eventModel?.startDate?.let { showDatePicker(it, viewModel::setStartDate) } }
        startTime.setOnClickListener { currentState?.eventModel?.startTime?.let { showTimePicker(it, viewModel::setStartTime) } }
        endDate.setOnClickListener {
            val model = currentState?.eventModel ?: return@setOnClickListener
            showDatePicker(model.endDate ?: model.startDate, viewModel::setEndDate)
        }
        endTime.setOnClickListener {
            val model = currentState?.eventModel ?: return@setOnClickListener
            val defaultTime = model.endTime ?: GuildScheduledEventPickerDateTime
                .generateDefaultEndDateTime(model.startDate, model.startTime).second
            showTimePicker(defaultTime, viewModel::setEndTime)
        }
        next.setOnClickListener { openPreview() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildScheduledEventSettings::class.java) { configureUi(it) }
    }

    private fun configureUi(state: GuildScheduledEventSettingsViewModel.ViewState) {
        if (state is GuildScheduledEventSettingsViewModel.ViewState.Invalid) {
            activity?.finish()
            return
        }
        state as GuildScheduledEventSettingsViewModel.ViewState.Initialized
        currentState = state
        val model = state.eventModel
        if (topic.text?.toString() != model.name) topic.setText(model.name)
        if (description.text?.toString() != model.description) description.setText(model.description)
        val startMillis = GuildScheduledEventPickerDateTime.toMillis(model.startDate, model.startTime)
        startDate.setText(DateUtils.formatDateTime(requireContext(), startMillis, DateUtils.FORMAT_SHOW_DATE))
        startTime.setText(DateUtils.formatDateTime(requireContext(), startMillis, DateUtils.FORMAT_SHOW_TIME))
        val external = model.entityType == GuildScheduledEventEntityType.EXTERNAL
        listOf(endDateHeader, endTimeHeader, endDateLayout, endTimeLayout).forEach {
            it.visibility = if (external) View.VISIBLE else View.GONE
        }
        if (external) {
            endDate.setText(model.endDate?.let { DateUtils.formatDateTime(requireContext(), it.toMillis(), DateUtils.FORMAT_SHOW_DATE) }.orEmpty())
            endTime.setText(
                if (model.endDate != null && model.endTime != null) {
                    DateUtils.formatDateTime(
                        requireContext(),
                        GuildScheduledEventPickerDateTime.toMillis(model.endDate, model.endTime),
                        DateUtils.FORMAT_SHOW_TIME,
                    )
                } else "",
            )
        }
        next.isEnabled = viewModel.isNextButtonEnabled()
    }

    private fun openPreview() {
        val state = currentState ?: return
        when {
            viewModel.hasStartTimeChanged(state) &&
                !viewModel.isDateInFuture(state.eventModel.startDate, state.eventModel.startTime) ->
                AppToast.show(context, R.string.guild_event_past_start_date)
            viewModel.hasEndTimeChanged(state) &&
                !viewModel.isDateInFuture(state.eventModel.endDate, state.eventModel.endTime) ->
                AppToast.show(context, R.string.guild_event_past_end_date_1)
            !viewModel.isStartDateBeforeEndDate(state) ->
                AppToast.show(context, R.string.guild_event_end_date_before_start_date)
            else -> WidgetPreviewGuildScheduledEvent.launch(
                requireContext(),
                state.eventModel,
                existingEventId?.let { WidgetPreviewGuildScheduledEvent.ExistingEventData(it, WidgetPreviewGuildScheduledEvent.Action.EDIT_EVENT) },
                previewLauncher,
                fromSettings = true,
            )
        }
    }

    private fun showDatePicker(
        date: GuildScheduledEventPickerDate,
        setter: (Int, Int, Int) -> GuildScheduledEventSettingsViewModel.DateError?,
    ) {
        DatePickerDialog(requireContext(), { _, year, month, day -> setter(year, month, day) }, date.year, date.month, date.dayOfMonth).show()
    }

    private fun showTimePicker(
        time: GuildScheduledEventPickerTime,
        setter: (Int, Int) -> GuildScheduledEventSettingsViewModel.DateError?,
    ) {
        TimePickerDialog(requireContext(), { _, hour, minute -> setter(hour, minute) }, time.hourOfDay, time.minute, false).show()
    }

    private fun TextInputEditText.watch(update: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(value: Editable?) = update(value?.toString().orEmpty())
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_EVENT_ID = "com.discord.intent.extra.EXTRA_GUILD_SCHEDULED_EVENT_ID"
        private const val EXTRA_ENTITY_TYPE = "EXTRA_ENTITY_TYPE"
        private const val EXTRA_EXTERNAL_LOCATION = "EXTRA_EXTERNAL_LOCATION"
        private const val RESULT_QUIT = 2

        @JvmStatic
        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            guildId: Long,
            entityType: GuildScheduledEventEntityType,
            channelId: Long?,
            externalLocation: String?,
            existingEventId: Long?,
        ) {
            val extras = Intent()
                .putExtra(EXTRA_GUILD_ID, guildId)
                .putExtra(EXTRA_ENTITY_TYPE, entityType)
                .apply {
                    channelId?.let { putExtra(EXTRA_CHANNEL_ID, it) }
                    externalLocation?.let { putExtra(EXTRA_EXTERNAL_LOCATION, it) }
                    existingEventId?.let { putExtra(EXTRA_EVENT_ID, it) }
                }
            AppScreenRouter.launchForResult(context, launcher, WidgetGuildScheduledEventSettings::class.java, extras)
        }
    }
}
