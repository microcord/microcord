package com.discord.widgets.chat.list.adapter

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.discord.R
import com.discord.i18n.I18nHooks
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.utilities.icon.IconUtils
import com.discord.views.PileView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.views.AvatarDecorationView
import com.discord.widgets.chat.list.entries.StartOfPrivateChatEntry
import com.discord.views.VitoImageView

/**
 * Head of a DM or group DM: the recipient's (or group's) avatar, its name, the
 * "this is the beginning of…" line, and — for one-to-one DMs only — the servers the two of you
 * share.
 */
class WidgetChatListAdapterItemPrivateChannelStart(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_private_channel_start, adapter) {

    private val header: TextView =
        itemView.findViewById(R.id.chat_list_adapter_item_private_channel_start_header)
    private val mutualGuildPile: PileView = itemView.findViewById(R.id.mutual_guild_pile)
    private val mutualGuilds: LinearLayout = itemView.findViewById(R.id.mutual_guilds)
    private val mutualGuildsText: TextView = itemView.findViewById(R.id.mutual_guilds_text)
    private val channelImage: VitoImageView =
        itemView.findViewById(R.id.private_channel_start_image)
    private val channelText: TextView = itemView.findViewById(R.id.private_channel_start_text)
    private val channelImageDecoration: AvatarDecorationView =
        itemView.findViewById(R.id.private_channel_start_image_decoration)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as StartOfPrivateChatEntry

        val channelName = entry.channelName.takeUnless(String::isBlank)
            ?: header.context.getString(R.string.unnamed)
        header.text = channelName

        IconUtils.setIcon(
            channelImage,
            entry.channelIcon ?: DEFAULT_GROUP_ICON,
            R.dimen.avatar_size_xxlarge,
        )
        channelImageDecoration.bind(entry.avatarDecoration)

        if (entry.isSystemDM) {
            channelText.setText(R.string.system_dm_empty_message)
        } else {
            // `beginning_dm` and `beginning_group_dm` name their placeholder differently, so both
            // tokens are supplied and the unused one is simply never matched.
            channelText.text = I18nHooks.format(
                channelText.context,
                startResId(entry.channelType),
                mapOf("username" to channelName, "name" to channelName),
            )
        }

        // Mutual servers are only meaningful between two people; a group DM has no single "other".
        val showMutualGuilds = !entry.isSystemDM && entry.channelType == CHANNEL_TYPE_DM
        mutualGuilds.visibility = if (showMutualGuilds) View.VISIBLE else View.GONE
        if (!showMutualGuilds) return

        mutualGuildPile.setItems(
            entry.mutualGuilds.map { guild ->
                PileView.Item(
                    getImageUri = { size -> IconUtils.getForGuild(guild, size = size) },
                    getInitials = { guild.shortName },
                )
            },
        )
        mutualGuildsText.text = if (entry.mutualGuilds.isNotEmpty()) {
            mutualGuildsText.i18nPlural(
                R.plurals.user_profile_mutual_guilds_count,
                entry.mutualGuilds.size,
                "count" to entry.mutualGuilds.size,
            )
        } else {
            mutualGuildsText.i18nFormat(R.string.no_mutual_guilds)
        }
    }

    @StringRes
    private fun startResId(channelType: Int): Int =
        if (channelType == CHANNEL_TYPE_GROUP_DM) R.string.beginning_group_dm else R.string.beginning_dm

    private companion object {
        private const val CHANNEL_TYPE_DM = 1
        private const val CHANNEL_TYPE_GROUP_DM = 3

        /** The bundled group-DM placeholder art, used when the group has no uploaded icon. */
        private const val DEFAULT_GROUP_ICON = "asset://asset/images/default_icon.jpg"
    }
}
