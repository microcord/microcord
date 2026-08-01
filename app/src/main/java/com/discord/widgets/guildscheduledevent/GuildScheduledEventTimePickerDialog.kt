package com.discord.widgets.guildscheduledevent

import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import android.widget.TimePicker
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton

internal class GuildScheduledEventTimePickerDialog : AppDialog(R.layout.guild_scheduled_event_time_picker_dialog) {

    private var hourOfDay = 0
    private var minute = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hourOfDay = savedInstanceState?.getInt(STATE_HOUR) ?: argumentsOrDefault.getInt(ARG_HOUR)
        minute = savedInstanceState?.getInt(STATE_MINUTE) ?: argumentsOrDefault.getInt(ARG_MINUTE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_HOUR, hourOfDay)
        outState.putInt(STATE_MINUTE, minute)
        super.onSaveInstanceState(outState)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val title = getString(R.string.material_timepicker_select_time)
        view.findViewById<TextView>(R.id.guild_scheduled_event_time_picker_title).text = title
        @Suppress("DEPRECATION")
        view.findViewById<TimePicker>(R.id.guild_scheduled_event_time_picker_input).apply {
            contentDescription = title
            setIs24HourView(DateFormat.is24HourFormat(requireContext()))
            currentHour = hourOfDay
            currentMinute = this@GuildScheduledEventTimePickerDialog.minute
            setOnTimeChangedListener { _, hour, selectedMinute ->
                hourOfDay = hour
                this@GuildScheduledEventTimePickerDialog.minute = selectedMinute
            }
        }
        view.findViewById<MaterialButton>(R.id.guild_scheduled_event_time_picker_cancel)
            .setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.guild_scheduled_event_time_picker_done).setOnClickListener {
            parentFragmentManager.setFragmentResult(
                argumentsOrDefault.getString(ARG_REQUEST_KEY).orEmpty(),
                Bundle().apply {
                    putInt(RESULT_HOUR, hourOfDay)
                    putInt(RESULT_MINUTE, minute)
                },
            )
            dismiss()
        }
    }

    override fun onPause() {
        view?.findViewById<TimePicker>(R.id.guild_scheduled_event_time_picker_input)?.clearFocus()
        super.onPause()
    }

    companion object {
        internal const val RESULT_HOUR = "hour"
        internal const val RESULT_MINUTE = "minute"
        private const val ARG_REQUEST_KEY = "request_key"
        private const val ARG_HOUR = "hour"
        private const val ARG_MINUTE = "minute"
        private const val STATE_HOUR = "state_hour"
        private const val STATE_MINUTE = "state_minute"

        fun show(
            fragmentManager: FragmentManager,
            requestKey: String,
            time: GuildScheduledEventPickerTime,
        ) {
            GuildScheduledEventTimePickerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putInt(ARG_HOUR, time.hourOfDay)
                    putInt(ARG_MINUTE, time.minute)
                }
            }.show(fragmentManager, GuildScheduledEventTimePickerDialog::class.java.name)
        }
    }
}
