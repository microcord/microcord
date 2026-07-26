package com.discord.dialogs.useractions

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.views.ThemedColor
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The long-press action sheet for another user: block, unblock, or remove friend.
 *
 * Exactly one of block/unblock is ever visible, and remove-friend only when there is a friendship to
 * remove, so the dialog is two rows tall at most. All three actions are terminal — the dialog toasts
 * the outcome and closes.
 */
class UserActionsDialog : AppDialog(R.layout.user_actions_dialog) {

    private val viewModel: UserActionsDialogViewModel by appViewModels {
        UserActionsDialogViewModel(
            userId = argumentsOrDefault.getLong(ARG_USER_ID, 0L),
            channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID, 0L),
        )
    }

    /** The avatar URL rarely changes; skipping identical requests avoids a re-decode per emission. */
    private val avatarChangeDetector = MGImages.DistinctChangeDetector()

    private lateinit var avatar: SimpleDraweeView
    private lateinit var userName: TextView
    private lateinit var blockItem: TextView
    private lateinit var unblockItem: TextView
    private lateinit var removeFriendItem: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        avatar = view.findViewById(R.id.user_actions_dialog_avatar)
        userName = view.findViewById(R.id.user_actions_dialog_user_name)
        blockItem = view.findViewById(R.id.user_actions_dialog_block_item)
        unblockItem = view.findViewById(R.id.user_actions_dialog_unblock_item)
        removeFriendItem = view.findViewById(R.id.user_actions_dialog_remove_friend_item)

        blockItem.setOnClickListener { viewModel.blockUser() }
        unblockItem.setOnClickListener {
            viewModel.removeRelationship(R.string.user_has_been_unblocked)
        }
        removeFriendItem.setOnClickListener {
            viewModel.removeRelationship(R.string.friend_has_been_deleted)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(UserActionsDialog::class.java, context) { state ->
                if (state is UserActionsDialogViewModel.ViewState.Loaded) updateView(state)
            }
        viewModel.observeEvents()
            .ui(this)
            .appSubscribe(UserActionsDialog::class.java, context) { handleEvent(it) }
    }

    private fun updateView(state: UserActionsDialogViewModel.ViewState.Loaded) {
        IconUtils.setIcon(
            imageView = avatar,
            url = IconUtils.getForUser(state.user, allowAnimation = true),
            changeDetector = avatarChangeDetector,
        )
        // A guild nickname replaces the username entirely; the discriminator is always shown, muted
        // and at the same size, which is what the reference's UserNameFormatter produces.
        val discriminated = state.user.getUserNameWithDiscriminator(
            discriminatorColor = ThemedColor.resolve(requireContext(), R.attr.colorTextMuted),
        )
        userName.text = state.userNickname ?: discriminated

        blockItem.visibility = if (state.showBlockItem) View.VISIBLE else View.GONE
        unblockItem.visibility = if (state.showUnblockItem) View.VISIBLE else View.GONE
        removeFriendItem.visibility = if (state.showRemoveFriendItem) View.VISIBLE else View.GONE
    }

    private fun handleEvent(event: UserActionsDialogViewModel.Event) {
        val messageRes = when (event) {
            is UserActionsDialogViewModel.Event.Success -> event.successMessageStringRes
            is UserActionsDialogViewModel.Event.Failure -> event.failureMessageStringRes
        }
        AppToast.show(context, messageRes)
        dismiss()
    }

    companion object {
        private const val ARG_USER_ID = "com.discord.intent.extra.EXTRA_USER_ID"
        private const val ARG_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        /** @param channelId supplies the guild whose nickname is shown; pass 0 outside a guild. */
        fun show(fragmentManager: FragmentManager, userId: Long, channelId: Long) {
            UserActionsDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_USER_ID, userId)
                    putLong(ARG_CHANNEL_ID, channelId)
                }
            }.show(fragmentManager, UserActionsDialog::class.java.name)
        }
    }
}
