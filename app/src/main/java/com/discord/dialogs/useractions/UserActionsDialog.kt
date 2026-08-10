package com.discord.dialogs.useractions

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.discord.i18n.i18nFormat
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.user.getSpannableForUserNameWithDiscrim
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The overflow menu for another user, opened from the profile's ⋯ button or a long press.
 *
 * Rows come in two groups, as the reference's context menu does: what you can do *with* someone,
 * then what you can do *about* them. Which rows appear depends on the relationship — block and
 * unblock are mutually exclusive, and most rows are terminal, toasting the outcome and closing.
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
    private lateinit var ignoreItem: TextView
    private lateinit var unignoreItem: TextView
    private lateinit var removeFriendItem: TextView
    private lateinit var friendNicknameItem: TextView
    private lateinit var primaryGroup: ViewGroup
    private lateinit var relationshipGroup: ViewGroup
    private lateinit var groupSeparator: View

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // The rounded surface has to clip, or a row's pressed highlight paints over its corners.
        view.clipToOutline = true
        primaryGroup = view.findViewById(R.id.user_actions_dialog_primary_group)
        relationshipGroup = view.findViewById(R.id.user_actions_dialog_relationship_group)
        groupSeparator = view.findViewById(R.id.user_actions_dialog_group_separator)
        avatar = view.findViewById(R.id.user_actions_dialog_avatar)
        userName = view.findViewById(R.id.user_actions_dialog_user_name)
        blockItem = view.findViewById(R.id.user_actions_dialog_block_item)
        unblockItem = view.findViewById(R.id.user_actions_dialog_unblock_item)
        ignoreItem = view.findViewById(R.id.user_actions_dialog_ignore_item)
        unignoreItem = view.findViewById(R.id.user_actions_dialog_unignore_item)
        removeFriendItem = view.findViewById(R.id.user_actions_dialog_remove_friend_item)
        friendNicknameItem = view.findViewById(R.id.user_actions_dialog_friend_nickname_item)

        listOf(blockItem, unblockItem, ignoreItem, unignoreItem, removeFriendItem, friendNicknameItem)
            .forEach(::restoreIconOpacity)

        blockItem.setOnClickListener { viewModel.blockUser() }
        unblockItem.setOnClickListener {
            viewModel.removeRelationship(R.string.user_has_been_unblocked)
        }
        ignoreItem.setOnClickListener { confirmIgnore() }
        unignoreItem.setOnClickListener { viewModel.unignoreUser() }
        removeFriendItem.setOnClickListener {
            viewModel.removeRelationship(R.string.friend_has_been_deleted)
        }
        friendNicknameItem.setOnClickListener {
            val state = viewModel.viewState as? UserActionsDialogViewModel.ViewState.Loaded
                ?: return@setOnClickListener
            FriendNicknameDialog.show(parentFragmentManager, state.user.id, state.friendNickname)
            dismiss()
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
        userName.text = getSpannableForUserNameWithDiscrim(
            user = state.user,
            // The dialog already resolves the friend nickname for its add/change label; the name at
            // the top used only the guild nick, so the same sheet showed two different names.
            nickname = state.userNickname ?: state.friendNickname,
            context = requireContext(),
            primaryColorRes = R.attr.colorHeaderPrimary,
            primaryFont = R.attr.font_primary_semibold,
            primaryTextSizeSp = R.integer.uikit_textsize_large_sp,
            secondaryColorRes = R.attr.colorTextMuted,
            secondaryFont = R.attr.font_primary_normal,
            secondaryTextSizeSp = R.integer.uikit_textsize_large_sp,
        )

        blockItem.visibility = if (state.showBlockItem) View.VISIBLE else View.GONE
        unblockItem.visibility = if (state.showUnblockItem) View.VISIBLE else View.GONE
        ignoreItem.visibility = if (state.showIgnoreItem) View.VISIBLE else View.GONE
        unignoreItem.visibility = if (state.showUnignoreItem) View.VISIBLE else View.GONE
        removeFriendItem.visibility = if (state.showRemoveFriendItem) View.VISIBLE else View.GONE
        friendNicknameItem.visibility = if (state.showFriendNicknameItem) View.VISIBLE else View.GONE
        friendNicknameItem.setText(
            if (state.friendNickname == null) R.string.add_friend_nickname else R.string.change_friend_nickname,
        )

        // The band between the two groups is only a separator, so it goes when either side of it
        // has nothing left to separate.
        primaryGroup.visibility = if (primaryGroup.hasVisibleChild()) View.VISIBLE else View.GONE
        relationshipGroup.visibility =
            if (relationshipGroup.hasVisibleChild()) View.VISIBLE else View.GONE
        groupSeparator.visibility =
            if (primaryGroup.isVisible && relationshipGroup.isVisible) View.VISIBLE else View.GONE
    }

    private fun ViewGroup.hasVisibleChild(): Boolean =
        (0 until childCount).any { getChildAt(it).visibility == View.VISIBLE }

    /**
     * These icons carry `android:alpha="0.6"` in the asset, which a tint multiplies rather than
     * replaces — so tinting them to the label colour still left them looking disabled. The
     * reference draws its menu icons at the label's full strength.
     */
    private fun restoreIconOpacity(row: TextView) {
        row.compoundDrawablesRelative.firstOrNull()?.mutate()?.alpha = 255
    }

    /**
     * Ignoring is explained before it happens.
     *
     * The reference gates it behind a sheet listing what ignoring does and does not do, because it
     * is easily mistaken for blocking, and offers the stronger action from inside that sheet.
     */
    private fun confirmIgnore() {
        IgnoreUserConfirmationSheet.show(
            fragmentManager = parentFragmentManager,
            userId = viewModel.userIdForConfirmation,
            onConfirmIgnore = viewModel::ignoreUser,
            onBlockInstead = viewModel::blockUser,
        )
    }

    private fun handleEvent(event: UserActionsDialogViewModel.Event) {
        val messageRes = when (event) {
            is UserActionsDialogViewModel.Event.Success -> event.successMessageStringRes
            is UserActionsDialogViewModel.Event.Failure -> event.failureMessageStringRes
        }
        val username = (event as? UserActionsDialogViewModel.Event.Success)?.username
        if (username == null) {
            AppToast.show(context, messageRes)
        } else {
            AppToast.show(context, requireContext().i18nFormat(messageRes, "username" to username))
        }
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
