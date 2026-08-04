package com.discord.dialogs.useractions

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.displayName
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.discord.nullserializable.NullSerializable

/**
 * Sets or clears the nickname for a friend.
 *
 * The nickname is per-relationship and only ever shown outside guilds, so the placeholder is the
 * user's own display name — what the row will fall back to when the field is cleared.
 */
class FriendNicknameDialog : AppDialog(R.layout.friend_nickname_dialog) {

    private val userId: Long by lazy { argumentsOrDefault.getLong(ARG_USER_ID) }

    private lateinit var input: TextInputLayout

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        input = view.findViewById(R.id.friend_nickname_dialog_input)
        input.editText?.setText(argumentsOrDefault.getString(ARG_NICKNAME).orEmpty())
        input.editText?.text?.let { input.editText?.setSelection(it.length) }
        input.placeholderText = StoreStream.getUsers().getUsers()[userId]?.displayName
        input.setEndIconOnClickListener { input.editText?.setText("") }
        input.setOnImeActionDone { save() }

        view.findViewById<MaterialButton>(R.id.friend_nickname_dialog_save).setOnClickListener { save() }
        view.findViewById<MaterialButton>(R.id.friend_nickname_dialog_cancel).setOnClickListener { dismiss() }
    }

    private fun save() {
        val api = RestAPI.apiOrNull ?: return dismiss()
        val typed = input.getTextOrEmpty().trim()
        val nickname = if (typed.isEmpty()) NullSerializable.Null() else NullSerializable.Value(typed)
        api.updateRelationshipNickname(userId, RestAPIParams.FriendNickname(nickname))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = FriendNicknameDialog::class.java,
                errorHandler = {
                    context?.let { AppToast.show(it, R.string.default_failure_to_perform_action_message) }
                    dismiss()
                },
            ) { dismiss() }
    }

    companion object {
        private const val ARG_USER_ID = "INTENT_EXTRA_USER_ID"
        private const val ARG_NICKNAME = "INTENT_EXTRA_NICKNAME"

        fun show(fragmentManager: FragmentManager, userId: Long, nickname: String?) {
            FriendNicknameDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_USER_ID, userId)
                    putString(ARG_NICKNAME, nickname)
                }
            }.show(fragmentManager, FriendNicknameDialog::class.java.name)
        }
    }
}
