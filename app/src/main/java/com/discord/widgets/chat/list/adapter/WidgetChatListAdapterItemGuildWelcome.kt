package com.discord.widgets.chat.list.adapter

import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.i18n.setI18nText
import com.discord.utilities.time.Clock
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.ChatActionItem
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.GuildWelcomeEntry
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.servers.WidgetServerSettingsOverview

/**
 * The panel that replaces "this is the beginning of #channel" in a brand new guild.
 *
 * It offers the two setup actions the viewer is actually allowed to take, so an owner who has not
 * uploaded an icon sees that card and a member who may invite sees only the invite card. When
 * neither applies the intro line is hidden as well, leaving a plain welcome message.
 */
class WidgetChatListAdapterItemGuildWelcome @JvmOverloads constructor(
    adapter: WidgetChatListAdapter,
    private val clock: Clock = adapter.clock,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_guild_welcome, adapter) {

    private val header: TextView = itemView.findViewById(R.id.item_guild_welcome_header)
    private val subheader: LinkifiedTextView = itemView.findViewById(R.id.item_guild_welcome_subheader)
    private val subheaderIntro: LinkifiedTextView =
        itemView.findViewById(R.id.item_guild_welcome_subheader_intro)
    private val inviteFriends: ChatActionItem = itemView.findViewById(R.id.welcome_card_invite_friends)
    private val uploadIcon: ChatActionItem = itemView.findViewById(R.id.welcome_card_upload_icon)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as? GuildWelcomeEntry ?: return

        header.text = header.i18nFormat(R.string.welcome_cta_title_mobile, "guildName" to entry.guildName)

        uploadIcon.visibility = if (!entry.guildHasIcon && entry.isOwner) View.VISIBLE else View.GONE
        uploadIcon.setOnClickListener { view ->
            WidgetServerSettingsOverview.launch(view.context, entry.guildId, openAvatarPicker = true)
        }

        inviteFriends.visibility = if (entry.canInvite) View.VISIBLE else View.GONE
        inviteFriends.setOnClickListener { view ->
            WidgetGuildInviteShare.launch(
                view.context,
                adapter.fragmentManager,
                entry.guildId,
                source = INVITE_SOURCE,
            )
        }

        // The "here are some steps" line only makes sense when at least one step is on screen.
        val hasAnyAction = uploadIcon.visibility == View.VISIBLE || inviteFriends.visibility == View.VISIBLE
        subheaderIntro.visibility = if (hasAnyAction) View.VISIBLE else View.GONE
        subheaderIntro.movementMethod = LinkMovementMethod.getInstance()
        subheaderIntro.setI18nText(
            R.string.welcome_cta_subtitle_action_with_guide,
            "guideURL" to GETTING_STARTED_GUIDE_URL,
        )

        // A guild older than a month is not "brand new, shiny" whatever the viewer's role is.
        val isEstablishedGuild =
            TimeUtils.parseSnowflake(entry.guildId) < clock.currentTimeMillis() - OLD_GUILD_AGE_THRESHOLD
        subheader.setText(
            when {
                isEstablishedGuild -> R.string.welcome_cta_subtitle_existing_server
                entry.isOwner -> R.string.welcome_cta_subtitle_owner
                else -> R.string.welcome_cta_subtitle_member
            },
        )
    }

    private companion object {
        /** Analytics source recorded against invites created from this panel. */
        private const val INVITE_SOURCE = "Welcome Message"

        private const val GETTING_STARTED_GUIDE_URL =
            "https://support.discord.com/hc/en-us/articles/360045138571" +
                "?utm_source=discord&utm_medium=blog&utm_campaign=2020-06_help-new-user&utm_content=--t%3Apm"
    }
}

/** A guild is no longer treated as new after thirty days. */
internal const val OLD_GUILD_AGE_THRESHOLD: Long = 2_592_000_000L
