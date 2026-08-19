package com.discord.widgets.settings

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import com.discord.R
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.isDM
import com.discord.api.channel.isSystemDM
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetSettingsDmListCustomizationBinding
import com.discord.stores.StoreStream
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.feature.DmListCustomization
import com.discord.utilities.feature.DmListCustomization.Component
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.presence.PresenceUtils

/**
 * Toggles for the private-channel rows of the DM list. The preview inflates the real row layout
 * and binds it with the first one-to-one DM (or a fixed sample when none is cached), re-binding
 * on every toggle so each change is visible immediately.
 */
class WidgetSettingsDmListCustomization : AppFragment(R.layout.widget_settings_dm_list_customization) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.dm_list_customization_title)
        setActionBarDisplayHomeAsUpEnabled()
        val binding = WidgetSettingsDmListCustomizationBinding.bind(view)
        val toggles = mapOf(
            Component.STATUS_DOT to binding.dmListCustomizationStatusDot,
            Component.STATUS to binding.dmListCustomizationStatus,
            Component.GUILD_TAG to binding.dmListCustomizationGuildTag,
            Component.MESSAGE to binding.dmListCustomizationMessagePreview,
            Component.TIMESTAMP to binding.dmListCustomizationTimestamp,
        )
        val renderPreview = { bindPreview(binding) }
        toggles.forEach { (component, toggle) ->
            toggle.apply {
                isChecked = DmListCustomization.visible(component)
                setOnCheckedListener { visible ->
                    DmListCustomization.setVisible(component, visible)
                    renderPreview()
                }
            }
        }
        renderPreview()
    }

    /** Binds the included real row layout against the current customization state. */
    private fun bindPreview(binding: WidgetSettingsDmListCustomizationBinding) {
        val row = binding.dmListCustomizationPreviewRow
        val channel = StoreStream.getChannels().getChannelsForGuild(PRIVATE_CHANNELS_GUILD_ID).values
            .firstOrNull { it.isDM() && !it.isSystemDM() }
        val recipient = channel?.getDMRecipient()
        val presence = recipient?.id?.let { StoreStream.getPresences().getPresences()[it] }

        row.channelsListItemPrivateName.text =
            recipient?.username ?: channel?.name ?: getString(R.string.direct_messages)
        IconUtils.setIcon(row.channelsListItemPrivateAvatar, channel, R.dimen.avatar_size_standard)

        row.channelListItemPrivateStatus.setPresence(presence)
        row.channelListItemPrivateStatus.visibility =
            if (DmListCustomization.visible(Component.STATUS_DOT) && presence != null) View.VISIBLE else View.GONE

        row.channelsListItemPrivateGuildTag.bind(null)
        row.channelsListItemPrivateGuildTag.setProfileColors(null, null)
        row.channelsListItemPrivateGuildTag.setOnClickListener(null)
        if (DmListCustomization.visible(Component.GUILD_TAG)) {
            // The sample tag keeps the component visible even when the preview DM has no server.
            row.channelsListItemPrivateGuildTag.setSampleTag(getString(R.string.dm_list_customization_sample_tag))
        } else {
            row.channelsListItemPrivateGuildTag.visibility = View.GONE
        }

        val desc = row.channelsListItemPrivateDesc
        val preview = channel
            ?.let { StoreStream.getMessages().getMessages(it.id).lastOrNull { m -> !m.content.isNullOrBlank() }?.content }
        if (DmListCustomization.visible(Component.MESSAGE)) {
            desc.text = preview ?: getString(R.string.dm_list_customization_sample_message)
        } else if (DmListCustomization.visible(Component.STATUS)) {
            PresenceUtils.setStatusLineWithLeadingIcon(presence, isApplicationStreaming = false, desc)
        } else {
            desc.visibility = View.GONE
        }

        val lastMessageId = channel?.lastMessageId ?: 0L
        row.channelsListItemPrivateTimestamp.visibility =
            if (DmListCustomization.visible(Component.TIMESTAMP)) {
                row.channelsListItemPrivateTimestamp.text = if (lastMessageId > 0L) {
                    DateUtils.getRelativeTimeSpanString(SnowflakeUtils.toTimestamp(lastMessageId)).toString()
                } else {
                    getString(R.string.dm_list_customization_sample_time)
                }
                View.VISIBLE
            } else {
                View.GONE
            }

        // The preview row is not interactive and never shows these.
        row.channelsListItemPrivateMentions.visibility = View.GONE
        row.channelsListItemPrivatePin.visibility = View.GONE
        row.root.isClickable = false
        row.root.isLongClickable = false
    }

    companion object {
        private const val PRIVATE_CHANNELS_GUILD_ID = 0L

        fun launch(context: Context) = AppScreenRouter.launch(
            context,
            WidgetSettingsDmListCustomization::class.java,
            Intent(),
        )
    }
}
