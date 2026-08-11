package com.discord.widgets.user.usersheet

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.discord.R
import com.discord.app.AppDialog
import com.discord.databinding.WidgetEditUserNoteBinding
import com.discord.utilities.view.extensions.getTextOrEmpty

/**
 * Edits the private note kept against a user.
 *
 * The reference opens the note in its own editor rather than letting it be typed inline on the
 * profile, so the profile itself only ever renders the note it already has.
 */
class WidgetEditUserNote : AppDialog(R.layout.widget_edit_user_note) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = WidgetEditUserNoteBinding.bind(view)
        val field = binding.editUserNoteInputField
        // Plain text so the note is never autocorrected, but still multi-line: setting a raw type
        // replaces the flags the layout asked for rather than adding to them.
        field.setRawInputType(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)
        // The inherited style asks for a Next action, which replaces the enter key; the note wants
        // the enter key.
        field.imeOptions = EditorInfo.IME_ACTION_NONE
        // Only seed on first creation; after a rotation the field restores what was being typed.
        if (savedInstanceState == null) {
            field.setText(argumentsOrDefault.getString(ARG_NOTE).orEmpty())
            field.setSelection(field.text?.length ?: 0)
        }
        binding.editUserNoteCancel.setOnClickListener { dismiss() }
        binding.editUserNoteSave.setOnClickListener {
            setFragmentResult(
                REQUEST_KEY,
                bundleOf(RESULT_NOTE to binding.editUserNoteInput.getTextOrEmpty()),
            )
            dismiss()
        }
    }

    companion object {
        const val REQUEST_KEY = "EDIT_USER_NOTE"
        const val RESULT_NOTE = "note"

        private const val ARG_NOTE = "note"
        private const val TAG = "WidgetEditUserNote"

        fun show(fragmentManager: FragmentManager, note: String) {
            WidgetEditUserNote()
                .apply { arguments = bundleOf(ARG_NOTE to note) }
                .show(fragmentManager, TAG)
        }

        fun bindResult(fragment: Fragment, onSaved: (String) -> Unit) {
            fragment.parentFragmentManager.setFragmentResultListener(
                REQUEST_KEY,
                fragment.viewLifecycleOwner,
            ) { _, bundle -> onSaved(bundle.getString(RESULT_NOTE).orEmpty()) }
        }
    }
}
