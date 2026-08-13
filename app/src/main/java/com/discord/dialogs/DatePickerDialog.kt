package com.discord.dialogs

import android.os.Bundle
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.databinding.DialogDatePickerBinding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedDateMillis = savedInstanceState?.getLong(STATE_SELECTED_DATE)
            ?: argumentsOrDefault.getLong(ARG_INITIAL_DATE)
        dateChanged = savedInstanceState?.getBoolean(STATE_DATE_CHANGED) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_SELECTED_DATE, selectedDateMillis)
        outState.putBoolean(STATE_DATE_CHANGED, dateChanged)
        super.onSaveInstanceState(outState)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (onDateSelected == null) {
            dismiss()
            return
        }

        val binding = DialogDatePickerBinding.bind(requireView())
        binding.dialogDatePickerTitle.text = argumentsOrDefault.getCharSequence(ARG_LABEL, "")

        val picker = binding.dialogDatePickerInput
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

        binding.dialogDatePickerDone.setOnClickListener {
            if (dateChanged || argumentsOrDefault.getBoolean(ARG_NOTIFY_UNCHANGED)) {
                onDateSelected?.invoke(selectedDateMillis)
            }
            dismiss()
        }
    }

    /** Spinners keep focus across a pause and swallow the next touch; clearing it avoids that. */
    override fun onPause() {
        super.onPause()
        view?.let(DialogDatePickerBinding::bind)?.dialogDatePickerInput?.clearFocus()
    }

    companion object {
        private const val ARG_LABEL = "label"
        private const val ARG_INITIAL_DATE = "initial_date"
        private const val ARG_MAX_DATE = "max_date"
        private const val ARG_NOTIFY_UNCHANGED = "notify_unchanged"
        private const val STATE_SELECTED_DATE = "selected_date"
        private const val STATE_DATE_CHANGED = "date_changed"

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
        ): DatePickerDialog = show(
            fragmentManager,
            label,
            initialDateMillis,
            maxDateMillis,
            false,
            onDateSelected,
        )

        fun show(
            fragmentManager: FragmentManager,
            label: CharSequence,
            initialDateMillis: Long,
            maxDateMillis: Long,
            notifyUnchangedOnDone: Boolean,
            onDateSelected: (Long) -> Unit,
        ): DatePickerDialog = DatePickerDialog().apply {
            arguments = Bundle().apply {
                putCharSequence(ARG_LABEL, label)
                putLong(ARG_INITIAL_DATE, initialDateMillis)
                putLong(ARG_MAX_DATE, maxDateMillis)
                putBoolean(ARG_NOTIFY_UNCHANGED, notifyUnchangedOnDone)
            }
            this.onDateSelected = onDateSelected
            show(fragmentManager, DatePickerDialog::class.java.name)
        }
    }
}
