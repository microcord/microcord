package com.discord.widgets.settings

import android.view.View
import com.discord.R
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.ClientStatuses
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsThemeComponentGalleryBinding
import com.discord.models.presence.Presence
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils

class WidgetSettingsThemeComponentGallery : AppFragment(R.layout.widget_settings_theme_component_gallery) {
    private var _binding: WidgetSettingsThemeComponentGalleryBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsThemeComponentGalleryBinding.bind(view)
        setActionBarTitle(R.string.theme_component_gallery)
        setActionBarDisplayHomeAsUpEnabled()

        binding.themeGallerySwitch.setChecked(true, false)
        binding.themeGalleryCheckbox.setChecked(true, false)
        binding.themeGalleryRadio.setChecked(true, false)
        binding.themeGalleryLoadingButton.setIsLoading(true)

        val me = StoreStream.getUsers().getMe()
        val avatarUrl = IconUtils.getForUser(me, size = 64)
        binding.themeGalleryAvatar.setImageURI(avatarUrl)
        binding.themeGalleryUsername.configureUsername(me.username)
        binding.themeGalleryUsername.configureTag(true, R.string.bot_tag_bot, true)
        binding.themeGalleryUsername.configureGuildTag(me.primaryGuild)

        binding.themeGalleryHeaderPreview.apply {
            setTitle(me.username)
            setAvatar(avatarUrl)
            setSubtitle(getString(R.string.theme_gallery_description))
            setIdentityVisibility(showIcon = true, showTitle = true)
            setPresence(StoreStream.getPresences().getLocalPresence(), visible = true)
        }

        binding.themeGalleryMessage.apply {
            chatListAdapterItemTextAvatar.setImageURI(avatarUrl)
            chatListAdapterItemTextStatus.setPresence(StoreStream.getPresences().getLocalPresence())
            chatListAdapterItemTextStatus.visibility = View.VISIBLE
            chatListAdapterItemTextAvatarDecoration.visibility = View.GONE
            chatListAdapterItemTextName.text = me.username
            chatListAdapterItemTextGuildTag.bind(me.primaryGuild)
            chatListAdapterItemTextRoleIcon.visibility = View.GONE
            chatListAdapterItemTextTag.apply {
                setText(R.string.bot_tag_bot)
                visibility = View.VISIBLE
            }
            chatListAdapterItemTextTimestamp.text = getString(R.string.message_customization_preview_timestamp)
            chatListAdapterItemText.text = getString(R.string.theme_gallery_message_body)
            chatListAdapterItemTextDecorator.visibility = View.GONE
            chatListAdapterItemTextDecoratorReplyLinkIcon.visibility = View.GONE
            chatListAdapterItemAttachments.visibility = View.GONE
            chatOverlayTypingDots.visibility = View.GONE
            chatListAdapterItemTextError.visibility = View.GONE
            chatListAdapterItemTextLoading.visibility = View.GONE
            chatListAdapterItemCommunicationDisabledIcon.visibility = View.GONE
        }

        binding.themeGalleryStatusOnline.setPresence(presence(ClientStatus.ONLINE, desktop = ClientStatus.ONLINE))
        binding.themeGalleryStatusIdle.setPresence(presence(ClientStatus.IDLE, desktop = ClientStatus.IDLE))
        binding.themeGalleryStatusDnd.setPresence(presence(ClientStatus.DND, desktop = ClientStatus.DND))
        binding.themeGalleryStatusOffline.setPresence(presence(ClientStatus.OFFLINE))
        binding.themeGalleryStatusMobile.setPresence(presence(ClientStatus.ONLINE, mobile = ClientStatus.ONLINE))

        binding.themeGalleryReaction.configure(MessageReaction(3, MessageReactionEmoji(null, "\uD83D\uDC4D", false), true), 1L, false)
        binding.themeGalleryTyping.start()
    }

    override fun onDestroyView() {
        _binding?.themeGalleryTyping?.stop()
        _binding = null
        super.onDestroyView()
    }

    private fun presence(
        status: ClientStatus,
        desktop: ClientStatus = ClientStatus.OFFLINE,
        mobile: ClientStatus = ClientStatus.OFFLINE,
    ) = Presence(status, ClientStatuses(desktop, ClientStatus.OFFLINE, mobile), null)
}
