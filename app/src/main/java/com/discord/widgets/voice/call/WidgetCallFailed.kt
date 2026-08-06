package com.discord.widgets.voice.call

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.i18nFormat
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.google.android.material.button.MaterialButton
import com.discord.i18n.i18nRender

/**
 * Explains why a call could not be placed: the two users are not friends.
 *
 * Its only action is to send the friend request, after which the dialog closes — the user is
 * expected to try the call again once the request is accepted.
 */
class WidgetCallFailed : AppDialog(R.layout.view_dialog_confirmation) {

    private lateinit var header: TextView
    private lateinit var body: TextView
    private lateinit var confirmButton: MaterialButton
    private lateinit var cancelButton: MaterialButton

    private val userId: Long
        get() = argumentsOrDefault.getLong(INTENT_USER_ID)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        header = view.findViewById(R.id.view_dialog_confirmation_header)
        body = view.findViewById(R.id.view_dialog_confirmation_text)
        confirmButton = view.findViewById(R.id.view_dialog_confirmation_confirm)
        cancelButton = view.findViewById(R.id.view_dialog_confirmation_cancel)

        header.text = getString(R.string.start_call)
        confirmButton.text = getString(R.string.add_friend_button)
        cancelButton.text = getString(R.string.okay)
        cancelButton.setOnClickListener { dismiss() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUsers().observeUser(userId)
            .ui(this)
            .appSubscribe(WidgetCallFailed::class.java) { configureUI(it) }
    }

    /**
     * A user that cannot be resolved leaves nothing to explain and nobody to befriend, so the
     * dialog closes rather than showing an empty prompt.
     */
    private fun configureUI(user: User?) {
        if (user == null) {
            dismiss()
            return
        }
        body.text = requireContext().i18nRender(
            R.string.call_invite_not_friends,
            "username" to user.username,
        )
        confirmButton.setOnClickListener { sendFriendRequest(user.id, user.username) }
    }

    private fun sendFriendRequest(userId: Long, username: String) {
        RestAPI.api.addRelationship(
            userId,
            RestAPIParams.UserRelationship(null, null),
            RELATIONSHIP_CONTEXT,
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetCallFailed::class.java,
                // The reference maps abort codes through `RestAPIAbortMessages`; that table is
                // not reconstructed yet, so every failure shows the generic message.
                errorHandler = { toast(getString(R.string.default_failure_to_perform_action_message)) },
            ) {
                toast(getString(R.string.friend_request_sent))
                dismiss()
            }
    }

    private fun toast(message: CharSequence) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val INTENT_USER_ID = "INTENT_USER_ID"

        /** Analytics location recorded with the friend request. */
        private const val RELATIONSHIP_CONTEXT = "Call"

        @JvmStatic
        fun show(fragmentManager: FragmentManager, userId: Long) {
            WidgetCallFailed().apply {
                arguments = Bundle().apply { putLong(INTENT_USER_ID, userId) }
            }.show(fragmentManager, WidgetCallFailed::class.java.simpleName)
        }
    }
}
