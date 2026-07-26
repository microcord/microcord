package com.discord.widgets.contact_sync

import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton

/**
 * "Friend request failed" — shown when the bulk friend-request call failed outright, and also when
 * it only partly succeeded, because the user cannot retry the individual failures from here.
 *
 * Reuses the shared confirmation layout with its cancel button hidden: there is nothing to cancel,
 * the flow always continues afterwards through [onClose].
 */
class AddFriendsFailed : AppDialog(R.layout.view_dialog_confirmation) {

    /** Invoked after the dialog is dismissed. The flow uses it to move past the suggestions step. */
    var onClose: (() -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.view_dialog_confirmation_header)
            .setText(R.string.friend_request_failed_header)
        view.findViewById<TextView>(R.id.view_dialog_confirmation_text)
            .setText(R.string.friend_request_failed_body)
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_confirm).apply {
            setText(R.string.okay)
            setOnClickListener {
                dismiss()
                onClose?.invoke()
            }
        }
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_cancel).visibility = View.GONE
    }

    companion object {
        @JvmStatic
        fun show(fragmentManager: FragmentManager): AddFriendsFailed =
            AddFriendsFailed().apply {
                show(fragmentManager, AddFriendsFailed::class.java.simpleName)
            }
    }
}
