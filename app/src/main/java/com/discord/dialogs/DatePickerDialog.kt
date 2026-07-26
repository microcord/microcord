package com.discord.dialogs

import android.os.Bundle
import android.widget.DatePicker
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton
import java.util.Calendar

/**
 * A spinner date picker in a dialog, used for the age gate and for scheduled-event dates.
 *
 * The listener fires on every spin *and* again on "Done", but only if the user actually changed
 * something ([dateChanged]) — otherwise opening the picker and cancelling out of it would silently
 * commit the pre-filled date.
 *
 * The listener is not parcelled, so a dialog restored after process death has nothing to report to
 * and closes itself.
 */
class DatePickerDialog : AppDialog(R.layout.dialog_date_picker) {

    var onDateSelected: ((Long) -> Unit)? = null

    private var selectedDateMillis: Long = 0
    private var dateChanged: Boolean = false

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (onDateSelected == null) {
            dismiss()
            return
        }

        val view = requireView()
        view.findViewById<TextView>(R.id.dialog_date_picker_title).text =
            argumentsOrDefault.getCharSequence(ARG_LABEL, "")

        selectedDateMillis = argumentsOrDefault.getLong(ARG_INITIAL_DATE)

        val picker = view.findViewById<DatePicker>(R.id.dialog_date_picker_input)
        picker.maxDate = argumentsOrDefault.getLong(ARG_MAX_DATE)
        picker.minDate = subtractYearsFromToday(MIN_DATE_YEARS_AGO)

        val calendar = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        picker.init(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ) { _, year, month, dayOfMonth ->
            selectedDateMillis = Calendar.getInstance()
                .apply { set(year, month, dayOfMonth) }
                .timeInMillis
            dateChanged = true
            onDateSelected?.invoke(selectedDateMillis)
        }

        view.findViewById<MaterialButton>(R.id.dialog_date_picker_done).setOnClickListener {
            if (dateChanged) onDateSelected?.invoke(selectedDateMillis)
            dismiss()
        }
    }

    /** Spinners keep focus across a pause and swallow the next touch; clearing it avoids that. */
    override fun onPause() {
        super.onPause()
        view?.findViewById<DatePicker>(R.id.dialog_date_picker_input)?.clearFocus()
    }

    companion object {
        private const val ARG_LABEL = "label"
        private const val ARG_INITIAL_DATE = "initial_date"
        private const val ARG_MAX_DATE = "max_date"

        /** Nobody using Discord was born more than 150 years ago. */
        private const val MIN_DATE_YEARS_AGO = 150

        /**
         * Reference: `BirthdayHelper.subtractYearsFromToday`, which this project has not
         * reconstructed — inlined here rather than inventing a home for it.
         */
        private fun subtractYearsFromToday(years: Int): Long =
            Calendar.getInstance().apply { add(Calendar.YEAR, -years) }.timeInMillis

        fun show(
            fragmentManager: FragmentManager,
            label: CharSequence,
            initialDateMillis: Long,
            maxDateMillis: Long,
            onDateSelected: (Long) -> Unit,
        ): DatePickerDialog = DatePickerDialog().apply {
            arguments = Bundle().apply {
                putCharSequence(ARG_LABEL, label)
                putLong(ARG_INITIAL_DATE, initialDateMillis)
                putLong(ARG_MAX_DATE, maxDateMillis)
            }
            this.onDateSelected = onDateSelected
            show(fragmentManager, DatePickerDialog::class.java.name)
        }
    }
}
