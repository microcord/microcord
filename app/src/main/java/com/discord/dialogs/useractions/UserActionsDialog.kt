package com.discord.dialogs.useractions

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.discord.i18n.i18nFormat
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui

/** The overflow menu for a user, anchored under the profile's ⋯ button. */
class UserActionsDialog : AppDialog(R.layout.user_actions_dialog) {

    private val viewModel: UserActionsDialogViewModel by appViewModels {
        UserActionsDialogViewModel(
            userId = argumentsOrDefault.getLong(ARG_USER_ID, 0L),
            channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID, 0L),
            guildId = argumentsOrDefault.getLong(ARG_GUILD_ID, 0L).takeIf { it != 0L },
            isShowingGuildProfile = argumentsOrDefault.getBoolean(ARG_SHOWING_GUILD_PROFILE),
        )
    }

    var onViewMainProfile: (() -> Unit)? = null
    var onViewServerProfile: (() -> Unit)? = null

    private lateinit var blockItem: TextView
    private lateinit var unblockItem: TextView
    private lateinit var ignoreItem: TextView
    private lateinit var unignoreItem: TextView
    private lateinit var friendNicknameItem: TextView
    private lateinit var copyUsernameItem: TextView
    private lateinit var copyLinkItem: TextView
    private lateinit var copyIdItem: TextView
    private lateinit var viewMainProfileItem: TextView
    private lateinit var viewServerProfileItem: TextView
    private lateinit var primaryGroup: ViewGroup
    private lateinit var relationshipGroup: ViewGroup
    private lateinit var groupSeparator: View

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        anchorWindow()
        primaryGroup = view.findViewById(R.id.user_actions_dialog_primary_group)
        relationshipGroup = view.findViewById(R.id.user_actions_dialog_relationship_group)
        groupSeparator = view.findViewById(R.id.user_actions_dialog_group_separator)
        blockItem = view.findViewById(R.id.user_actions_dialog_block_item)
        unblockItem = view.findViewById(R.id.user_actions_dialog_unblock_item)
        ignoreItem = view.findViewById(R.id.user_actions_dialog_ignore_item)
        unignoreItem = view.findViewById(R.id.user_actions_dialog_unignore_item)
        friendNicknameItem = view.findViewById(R.id.user_actions_dialog_friend_nickname_item)
        copyUsernameItem = view.findViewById(R.id.user_actions_dialog_copy_username_item)
        copyLinkItem = view.findViewById(R.id.user_actions_dialog_copy_link_item)
        copyIdItem = view.findViewById(R.id.user_actions_dialog_copy_id_item)
        viewMainProfileItem = view.findViewById(R.id.user_actions_dialog_view_main_profile_item)
        viewServerProfileItem = view.findViewById(R.id.user_actions_dialog_view_server_profile_item)

        listOf(
            blockItem, unblockItem, ignoreItem, unignoreItem, friendNicknameItem,
            copyUsernameItem, copyLinkItem, copyIdItem, viewMainProfileItem, viewServerProfileItem,
        ).forEach(::restoreIconOpacity)

        blockItem.setOnClickListener { viewModel.blockUser() }
        unblockItem.setOnClickListener {
            viewModel.removeRelationship(R.string.user_has_been_unblocked)
        }
        ignoreItem.setOnClickListener { confirmIgnore() }
        unignoreItem.setOnClickListener { viewModel.unignoreUser() }
        friendNicknameItem.setOnClickListener {
            val state = loadedState() ?: return@setOnClickListener
            FriendNicknameDialog.show(parentFragmentManager, state.user.id, state.friendNickname)
            dismiss()
        }
        copyUsernameItem.setOnClickListener {
            loadedState()?.let { copy("Username", it.user.username) }
        }
        copyLinkItem.setOnClickListener {
            loadedState()?.let { copy("Profile link", it.profileLink) }
        }
        copyIdItem.setOnClickListener {
            loadedState()?.let { copy("User ID", it.user.id.toString()) }
        }
        viewMainProfileItem.setOnClickListener {
            onViewMainProfile?.invoke()
            dismiss()
        }
        viewServerProfileItem.setOnClickListener {
            onViewServerProfile?.invoke()
            dismiss()
        }
    }

    private fun loadedState(): UserActionsDialogViewModel.ViewState.Loaded? =
        viewModel.viewState as? UserActionsDialogViewModel.ViewState.Loaded

    private fun copy(label: String, value: String) {
        requireContext().getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText(label, value))
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            AppToast.show(context, R.string.copied)
        }
        dismiss()
    }

    /** [Gravity.END] measures x from the right edge, so the anchor inset lines the two up. */
    private fun anchorWindow() {
        val window = dialog?.window ?: return
        val top = argumentsOrDefault.getInt(ARG_ANCHOR_TOP, ANCHOR_UNSET)
        if (top == ANCHOR_UNSET) return
        window.setGravity(Gravity.TOP or Gravity.END)
        window.setWindowAnimations(R.style.FadeInOut)
        window.attributes = window.attributes.apply {
            y = top
            x = argumentsOrDefault.getInt(ARG_ANCHOR_END_INSET, 0)
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
        blockItem.visibility = if (state.showBlockItem) View.VISIBLE else View.GONE
        unblockItem.visibility = if (state.showUnblockItem) View.VISIBLE else View.GONE
        ignoreItem.visibility = if (state.showIgnoreItem) View.VISIBLE else View.GONE
        unignoreItem.visibility = if (state.showUnignoreItem) View.VISIBLE else View.GONE
        friendNicknameItem.visibility = if (state.showFriendNicknameItem) View.VISIBLE else View.GONE
        copyIdItem.visibility = if (state.showCopyIdItem) View.VISIBLE else View.GONE
        viewMainProfileItem.visibility =
            if (state.showViewMainProfileItem && onViewMainProfile != null) View.VISIBLE else View.GONE
        viewServerProfileItem.visibility =
            if (state.showViewServerProfileItem && onViewServerProfile != null) View.VISIBLE else View.GONE
        friendNicknameItem.setText(
            if (state.friendNickname == null) R.string.add_friend_nickname else R.string.change_friend_nickname,
        )

        primaryGroup.visibility = if (primaryGroup.hasVisibleChild()) View.VISIBLE else View.GONE
        relationshipGroup.visibility =
            if (relationshipGroup.hasVisibleChild()) View.VISIBLE else View.GONE
        groupSeparator.visibility =
            if (primaryGroup.isVisible && relationshipGroup.isVisible) View.VISIBLE else View.GONE
    }

    private fun ViewGroup.hasVisibleChild(): Boolean =
        (0 until childCount).any { getChildAt(it).visibility == View.VISIBLE }

    /** The assets bake in `alpha="0.6"`, which a tint multiplies rather than replaces. */
    private fun restoreIconOpacity(row: TextView) {
        row.compoundDrawablesRelative.firstOrNull()?.mutate()?.alpha = 255
    }

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
        private const val ARG_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val ARG_SHOWING_GUILD_PROFILE = "com.discord.intent.extra.EXTRA_SHOWING_GUILD_PROFILE"

        private const val ARG_ANCHOR_TOP = "com.discord.intent.extra.EXTRA_ANCHOR_TOP"
        private const val ARG_ANCHOR_END_INSET = "com.discord.intent.extra.EXTRA_ANCHOR_END_INSET"

        private const val ANCHOR_UNSET = -1

        private const val ANCHOR_GAP_DP = 8

        /** @param anchor the button this hangs under; centred like any dialog without one. */
        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            userId: Long,
            channelId: Long,
            anchor: View? = null,
            guildId: Long? = null,
            isShowingGuildProfile: Boolean = false,
            onViewMainProfile: (() -> Unit)? = null,
            onViewServerProfile: (() -> Unit)? = null,
        ) {
            UserActionsDialog().apply {
                this.onViewMainProfile = onViewMainProfile
                this.onViewServerProfile = onViewServerProfile
                arguments = Bundle().apply {
                    putLong(ARG_USER_ID, userId)
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_GUILD_ID, guildId ?: 0L)
                    putBoolean(ARG_SHOWING_GUILD_PROFILE, isShowingGuildProfile)
                    anchor?.let { view ->
                        val location = IntArray(2).also(view::getLocationOnScreen)
                        val gap = (ANCHOR_GAP_DP * view.resources.displayMetrics.density).toInt()
                        val screenWidth = view.resources.displayMetrics.widthPixels
                        putInt(ARG_ANCHOR_TOP, location[1] + view.height + gap)
                        putInt(ARG_ANCHOR_END_INSET, screenWidth - location[0] - view.width)
                    }
                }
            }.show(fragmentManager, UserActionsDialog::class.java.name)
        }
    }
}
