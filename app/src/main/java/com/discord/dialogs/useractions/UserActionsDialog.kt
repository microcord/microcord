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
import com.discord.api.commands.ApplicationCommandIndexCommand
import com.discord.databinding.UserActionsDialogBinding
import com.discord.stores.StoreStream
import com.discord.widgets.chat.input.applauncher.WidgetContextCommandsSheet
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.plugins.PluginContextMenuHost
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuSurface
import dev.mantikafasi.manticord.plugins.api.pluginContextMenuContext

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

    private var _binding: UserActionsDialogBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        anchorWindow()
        _binding = UserActionsDialogBinding.bind(view)
        PluginContextMenuHost.bind(
            binding.pluginContextMenuItems,
            buildList {
                val guildId = argumentsOrDefault.getLong(ARG_GUILD_ID, 0L).takeIf { it > 0L }
                val channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID, 0L).takeIf { it > 0L }
                val userId = argumentsOrDefault.getLong(ARG_USER_ID, 0L)
                add(pluginContextMenuContext(PluginContextMenuSurface.USER, guildId, channelId, userId = userId, targetId = userId))
                if (guildId != null) {
                    add(pluginContextMenuContext(PluginContextMenuSurface.MEMBER, guildId, channelId, userId = userId, targetId = userId))
                }
            },
            ::dismiss,
        )

        listOf(
            binding.userActionsDialogBlockItem,
            binding.userActionsDialogUnblockItem,
            binding.userActionsDialogIgnoreItem,
            binding.userActionsDialogUnignoreItem,
            binding.userActionsDialogFriendNicknameItem,
            binding.userActionsDialogCopyUsernameItem,
            binding.userActionsDialogCopyLinkItem,
            binding.userActionsDialogCopyIdItem,
            binding.userActionsDialogViewMainProfileItem,
            binding.userActionsDialogViewServerProfileItem,
        ).forEach(::restoreIconOpacity)

        val channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID, 0L)
        val userId = argumentsOrDefault.getLong(ARG_USER_ID, 0L)
        // Outside a channel that has user commands (e.g. from the friends list), fall back to the
        // viewer's user-installed apps, which then run in the DM with this user.
        val commands = StoreStream.getApplicationCommands()
        val type = ApplicationCommandIndexCommand.TYPE_USER
        val commandChannelId = channelId.takeIf { commands.getContextCommands(it, type).isNotEmpty() } ?: 0L
        binding.userActionsDialogAppsItem.isVisible = commandChannelId != 0L ||
            (userId != StoreStream.getUsers().getMe().id && commands.getPrivateChannelContextCommands(type).isNotEmpty())
        binding.userActionsDialogAppsItem.setOnClickListener {
            dismiss()
            WidgetContextCommandsSheet.show(parentFragmentManager, commandChannelId, type, userId)
        }

        binding.userActionsDialogBlockItem.setOnClickListener { viewModel.blockUser() }
        binding.userActionsDialogUnblockItem.setOnClickListener {
            viewModel.removeRelationship(R.string.user_has_been_unblocked)
        }
        binding.userActionsDialogIgnoreItem.setOnClickListener { confirmIgnore() }
        binding.userActionsDialogUnignoreItem.setOnClickListener { viewModel.unignoreUser() }
        binding.userActionsDialogFriendNicknameItem.setOnClickListener {
            val state = loadedState() ?: return@setOnClickListener
            FriendNicknameDialog.show(parentFragmentManager, state.user.id, state.friendNickname)
            dismiss()
        }
        binding.userActionsDialogCopyUsernameItem.setOnClickListener {
            loadedState()?.let { copy("Username", it.user.username) }
        }
        binding.userActionsDialogCopyLinkItem.setOnClickListener {
            loadedState()?.let { copy("Profile link", it.profileLink) }
        }
        binding.userActionsDialogCopyIdItem.setOnClickListener {
            loadedState()?.let { copy("User ID", it.user.id.toString()) }
        }
        binding.userActionsDialogViewMainProfileItem.setOnClickListener {
            onViewMainProfile?.invoke()
            dismiss()
        }
        binding.userActionsDialogViewServerProfileItem.setOnClickListener {
            onViewServerProfile?.invoke()
            dismiss()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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
        binding.userActionsDialogBlockItem.visibility = if (state.showBlockItem) View.VISIBLE else View.GONE
        binding.userActionsDialogUnblockItem.visibility = if (state.showUnblockItem) View.VISIBLE else View.GONE
        binding.userActionsDialogIgnoreItem.visibility = if (state.showIgnoreItem) View.VISIBLE else View.GONE
        binding.userActionsDialogUnignoreItem.visibility = if (state.showUnignoreItem) View.VISIBLE else View.GONE
        binding.userActionsDialogFriendNicknameItem.visibility = if (state.showFriendNicknameItem) View.VISIBLE else View.GONE
        binding.userActionsDialogCopyIdItem.visibility = if (state.showCopyIdItem) View.VISIBLE else View.GONE
        binding.userActionsDialogViewMainProfileItem.visibility =
            if (state.showViewMainProfileItem && onViewMainProfile != null) View.VISIBLE else View.GONE
        binding.userActionsDialogViewServerProfileItem.visibility =
            if (state.showViewServerProfileItem && onViewServerProfile != null) View.VISIBLE else View.GONE
        binding.userActionsDialogFriendNicknameItem.setText(
            if (state.friendNickname == null) R.string.add_friend_nickname else R.string.change_friend_nickname,
        )

        binding.userActionsDialogPrimaryGroup.visibility =
            if (binding.userActionsDialogPrimaryGroup.hasVisibleChild()) View.VISIBLE else View.GONE
        binding.userActionsDialogRelationshipGroup.visibility =
            if (binding.userActionsDialogRelationshipGroup.hasVisibleChild()) View.VISIBLE else View.GONE
        binding.userActionsDialogGroupSeparator.visibility =
            if (binding.userActionsDialogPrimaryGroup.isVisible && binding.userActionsDialogRelationshipGroup.isVisible) {
                View.VISIBLE
            } else {
                View.GONE
            }
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
