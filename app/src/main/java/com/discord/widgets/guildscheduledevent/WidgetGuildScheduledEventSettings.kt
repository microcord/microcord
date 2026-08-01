package com.discord.widgets.guildscheduledevent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.View
import android.widget.DatePicker
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.dialogs.DatePickerDialog
import com.discord.i18n.I18nHooks
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.time.ClockFactory
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

    private val previewLauncher = WidgetPreviewGuildScheduledEvent.registerForResult(
        this,
        onEventCreated = { channelId, eventId, eventCreated ->
            activity?.let { host ->
                host.setResult(Activity.RESULT_OK)
                if (eventCreated) {
                    WidgetGuildScheduledEventCreateSuccess.launch(host, channelId, guildId, eventId)
                }
                host.finish()
            }
        },
        onQuit = {
            activity?.setResult(RESULT_QUIT)
            activity?.finish()
        },
    )

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
        parentFragmentManager.setFragmentResultListener(REQUEST_START_TIME, viewLifecycleOwner) { _, result ->
            showDateErrorToast(
                viewModel.setStartTime(
                    result.getInt(GuildScheduledEventTimePickerDialog.RESULT_HOUR),
                    result.getInt(GuildScheduledEventTimePickerDialog.RESULT_MINUTE),
                ),
            )
        }
        parentFragmentManager.setFragmentResultListener(REQUEST_END_TIME, viewLifecycleOwner) { _, result ->
            showDateErrorToast(
                viewModel.setEndTime(
                    result.getInt(GuildScheduledEventTimePickerDialog.RESULT_HOUR),
                    result.getInt(GuildScheduledEventTimePickerDialog.RESULT_MINUTE),
                ),
            )
        }
        startDate.setOnClickListener {
            currentState?.eventModel?.startDate?.let {
                showDatePicker(REQUEST_START_DATE, it, ClockFactory.get().currentTimeMillis())
            }
        }
        startTime.setOnClickListener {
            currentState?.eventModel?.startTime?.let { showTimePicker(REQUEST_START_TIME, it) }
        }
        endDate.setOnClickListener {
            val model = currentState?.eventModel ?: return@setOnClickListener
            val defaultDate = GuildScheduledEventPickerDateTime
                .generateDefaultEndDateTime(model.startDate, model.startTime).first
            showDatePicker(
                REQUEST_END_DATE,
                model.endDate ?: defaultDate,
                maxOf(model.startDate.toMillis(), ClockFactory.get().currentTimeMillis()),
            )
        }
        endTime.setOnClickListener {
            val model = currentState?.eventModel ?: return@setOnClickListener
            val defaultTime = model.endTime ?: GuildScheduledEventPickerDateTime
                .generateDefaultEndDateTime(model.startDate, model.startTime).second
            showTimePicker(REQUEST_END_TIME, defaultTime)
        }
        next.setOnClickListener { openPreview() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildScheduledEventSettings::class.java) { configureUi(it) }
        (parentFragmentManager.findFragmentByTag(DatePickerDialog::class.java.name) as? DatePickerDialog)?.let {
            bindDatePicker(it)
            view?.post { bindDatePicker(it) }
        }
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
        requestKey: String,
        date: GuildScheduledEventPickerDate,
        minDateMillis: Long,
    ) {
        val maxDateMillis = Calendar.getInstance().apply {
            set(2100, Calendar.DECEMBER, 31)
        }.timeInMillis
        val dialog = DatePickerDialog.show(
            parentFragmentManager,
            getString(R.string.date),
            date.toMillis(),
            maxDateMillis,
        ) { }
        dialog.requireArguments().apply {
            putString(ARG_DATE_REQUEST_KEY, requestKey)
            putLong(ARG_DATE_MIN_MILLIS, minDateMillis)
        }
        parentFragmentManager.executePendingTransactions()
        bindDatePicker(dialog)
    }

    private fun bindDatePicker(dialog: DatePickerDialog) {
        val requestKey = dialog.arguments?.getString(ARG_DATE_REQUEST_KEY) ?: return
        dialog.onDateSelected = { selectedDateMillis ->
            val arguments = dialog.requireArguments()
            if (arguments.containsKey(ARG_PENDING_DATE_MILLIS) &&
                arguments.getLong(ARG_PENDING_DATE_MILLIS) == selectedDateMillis
            ) {
                arguments.remove(ARG_PENDING_DATE_MILLIS)
                handleSelectedDate(requestKey, selectedDateMillis)
            } else {
                arguments.putLong(ARG_PENDING_DATE_MILLIS, selectedDateMillis)
            }
        }
        dialog.view?.findViewById<DatePicker>(R.id.dialog_date_picker_input)?.minDate =
            dialog.requireArguments().getLong(ARG_DATE_MIN_MILLIS)
    }

    private fun handleSelectedDate(requestKey: String, selectedDateMillis: Long) {
        val selected = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        val year = selected.get(Calendar.YEAR)
        val month = selected.get(Calendar.MONTH)
        val day = selected.get(Calendar.DAY_OF_MONTH)
        showDateErrorToast(
            when (requestKey) {
                REQUEST_START_DATE -> viewModel.setStartDate(year, month, day)
                REQUEST_END_DATE -> viewModel.setEndDate(year, month, day)
                else -> null
            },
        )
    }

    private fun showTimePicker(
        requestKey: String,
        time: GuildScheduledEventPickerTime,
    ) {
        GuildScheduledEventTimePickerDialog.show(parentFragmentManager, requestKey, time)
    }

    private fun showDateErrorToast(error: GuildScheduledEventSettingsViewModel.DateError?) {
        val message = when (error) {
            GuildScheduledEventSettingsViewModel.DateError.START_DATE_IN_PAST -> R.string.guild_event_past_start_date
            GuildScheduledEventSettingsViewModel.DateError.END_DATE_IN_PAST -> R.string.guild_event_past_end_date_1
            GuildScheduledEventSettingsViewModel.DateError.END_DATE_BEFORE_START_DATE ->
                R.string.guild_event_end_date_before_start_date
            else -> return
        }
        AppToast.show(context, message)
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
        private const val ARG_DATE_REQUEST_KEY = "guild_scheduled_event_date_request_key"
        private const val ARG_DATE_MIN_MILLIS = "guild_scheduled_event_date_min_millis"
        private const val ARG_PENDING_DATE_MILLIS = "guild_scheduled_event_pending_date_millis"
        private const val REQUEST_START_DATE = "guild_scheduled_event_start_date"
        private const val REQUEST_END_DATE = "guild_scheduled_event_end_date"
        private const val REQUEST_START_TIME = "guild_scheduled_event_start_time"
        private const val REQUEST_END_TIME = "guild_scheduled_event_end_time"

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

        fun registerForResult(
            fragment: Fragment,
            onFinished: () -> Unit,
            onQuit: () -> Unit,
        ): ActivityResultLauncher<Intent> = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            handleResult(result.resultCode, onFinished, onQuit)
        }

        internal fun handleResult(resultCode: Int, onFinished: () -> Unit, onQuit: () -> Unit) {
            when (resultCode) {
                Activity.RESULT_OK -> onFinished()
                RESULT_QUIT -> onQuit()
            }
        }
    }
}
