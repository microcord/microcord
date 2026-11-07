package com.discord.widgets.chat.list.actions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetInteractionInfoBinding
import com.discord.databinding.WidgetInteractionInfoRowBinding
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.user.usersheet.WidgetUserSheet

/**
 * Who an interaction message came from: the account or server that installed the application, and
 * the person who triggered it. Opened from the message menu, as "View Interaction Info" is on the
 * other clients.
 */
class WidgetInteractionInfo : AppBottomSheet() {
    private var _binding: WidgetInteractionInfoBinding? = null
    private val binding get() = checkNotNull(_binding)

    override fun getContentViewResId() = R.layout.widget_interaction_info

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetInteractionInfoBinding.bind(view)
        val channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID)
        val message = StoreStream.getMessages().getMessage(channelId, argumentsOrDefault.getLong(ARG_MESSAGE_ID))
        val metadata = message?.interactionMetadata
        if (message == null || metadata == null) {
            dismiss()
            return
        }
        val application = message.author?.username.orEmpty()
        val installerId = metadata.authorizingIntegrationOwners?.get(USER_INSTALL)
        val installerGuild = metadata.authorizingIntegrationOwners?.get(GUILD_INSTALL)
            ?.let { StoreStream.getGuilds().getGuild(it) }
        val installerUser = installerId?.let { id ->
            StoreStream.getUsers().getUsers()[id] ?: null.also { StoreStream.getUsers().fetchUsers(listOf(id)) }
        }
        val guildId = StoreStream.getChannels().getChannel(channelId)?.guildId?.takeIf { it != 0L }

        val installedText = getString(R.string.interaction_info_installed, application)
        if (installerGuild != null) {
            addRow(installerGuild.name, installedText, IconUtils.getForGuild(installerGuild, size = AVATAR_SIZE), null, guildId, channelId)
        } else if (installerUser != null) {
            addRow(installerUser.username, installedText, IconUtils.getForUser(installerUser, size = AVATAR_SIZE), installerUser.id, guildId, channelId)
        }
        metadata.user?.let { user ->
            addRow(
                user.username,
                getString(R.string.interaction_info_triggered),
                IconUtils.getForUser(user.id, user.avatar?.value, user.discriminator.toIntOrNull(), size = AVATAR_SIZE),
                user.id,
                guildId,
                channelId,
            )
        }
    }

    private fun addRow(title: String, subtext: String, iconUrl: String?, userId: Long?, guildId: Long?, channelId: Long) {
        val row = WidgetInteractionInfoRowBinding.inflate(LayoutInflater.from(requireContext()), binding.interactionInfoContainer, false)
        row.interactionInfoTitle.text = title
        row.interactionInfoSubtext.text = subtext
        IconUtils.setIcon(row.interactionInfoAvatar, iconUrl, R.dimen.avatar_size_standard)
        if (userId != null) {
            row.root.setOnClickListener {
                WidgetUserSheet.show(parentFragmentManager, userId, channelId, guildId)
                dismiss()
            }
        }
        binding.interactionInfoContainer.addView(row.root)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        private const val ARG_MESSAGE_ID = "INTENT_EXTRA_MESSAGE_ID"
        private const val AVATAR_SIZE = 96

        /** Installation contexts in `authorizing_integration_owners`. */
        private const val GUILD_INSTALL = 0
        private const val USER_INSTALL = 1

        fun show(fragmentManager: FragmentManager, channelId: Long, messageId: Long) =
            WidgetInteractionInfo().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_MESSAGE_ID, messageId)
                }
            }.show(fragmentManager, WidgetInteractionInfo::class.java.name)
    }
}
