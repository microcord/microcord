package com.discord.widgets.guild_automod

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.databinding.WidgetAutomodUserActionsBinding
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.guildcommunicationdisabled.start.WidgetEnableGuildCommunication
import com.discord.widgets.user.WidgetBanUser
import com.discord.widgets.user.WidgetKickUser
import com.discord.widgets.user.usersheet.WidgetUserSheet

class WidgetAutoModUserActions : AppBottomSheet() {
    private var _binding: WidgetAutomodUserActionsBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun getContentViewResId() = R.layout.widget_automod_user_actions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetAutomodUserActionsBinding.bind(view)
        val args = requireArguments()
        val guildId = args.getLong(ARG_GUILD_ID)
        val channelId = args.getLong(ARG_CHANNEL_ID)
        val userId = args.getLong(ARG_USER_ID)
        val username = args.getString(ARG_USERNAME).orEmpty()
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()[channelId]
        val canTarget = userId != StoreStream.getUsers().getMe().id
        val isMember = StoreStream.getGuilds().getMember(guildId, userId) != null

        binding.title.text = username.ifEmpty { getString(R.string.guild_automod_user_actions_title) }
        binding.viewProfile.setOnClickListener {
            dismiss()
            WidgetUserSheet.show(parentFragmentManager, userId, channelId, guildId)
        }
        binding.timeoutUser.apply {
            visibility = visible(canTarget && isMember && PermissionUtils.can(Permission.MODERATE_MEMBERS, permissions))
            setOnClickListener {
                dismiss()
                WidgetEnableGuildCommunication.launch(userId, guildId, parentFragmentManager)
            }
        }
        binding.kickUser.apply {
            visibility = visible(canTarget && isMember && PermissionUtils.can(Permission.KICK_MEMBERS, permissions))
            setOnClickListener {
                dismiss()
                WidgetKickUser.show(parentFragmentManager, guildId, userId, username)
            }
        }
        binding.banUser.apply {
            visibility = visible(canTarget && PermissionUtils.can(Permission.BAN_MEMBERS, permissions))
            setOnClickListener {
                dismiss()
                WidgetBanUser.show(parentFragmentManager, guildId, userId, username)
            }
        }
        binding.copyId.setOnClickListener {
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("User ID", userId.toString()))
            AppToast.show(requireContext(), R.string.copied_text)
            dismiss()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"
        private const val ARG_CHANNEL_ID = "ARG_CHANNEL_ID"
        private const val ARG_USER_ID = "ARG_USER_ID"
        private const val ARG_USERNAME = "ARG_USERNAME"

        fun show(
            fragmentManager: FragmentManager,
            guildId: Long,
            channelId: Long,
            userId: Long,
            username: String,
        ) {
            WidgetAutoModUserActions().apply {
                arguments = Bundle().apply {
                    putLong(ARG_GUILD_ID, guildId)
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_USER_ID, userId)
                    putString(ARG_USERNAME, username)
                }
            }.show(fragmentManager, WidgetAutoModUserActions::class.java.name)
        }

        private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE
    }
}
