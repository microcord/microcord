package com.discord.widgets.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton

class DiscardChangesDialog : AppDialog(R.layout.view_dialog_confirmation) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.view_dialog_confirmation_header)
            .setText(R.string.discard_changes)
        view.findViewById<TextView>(R.id.view_dialog_confirmation_text)
            .setText(R.string.discard_changes_description)
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_cancel)
            .setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_confirm).apply {
            setText(R.string.okay)
            setOnClickListener {
                dismiss()
                setFragmentResult(argumentsOrDefault.getString(ARG_REQUEST_KEY).orEmpty(), Bundle.EMPTY)
            }
        }
    }

    companion object {
        private const val ARG_REQUEST_KEY = "request_key"

        fun show(fragmentManager: FragmentManager, requestKey: String) {
            DiscardChangesDialog().apply {
                arguments = Bundle().apply { putString(ARG_REQUEST_KEY, requestKey) }
            }.show(fragmentManager, requestKey)
        }
    }
}
