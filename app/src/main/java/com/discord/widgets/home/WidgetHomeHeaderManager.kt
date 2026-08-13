package com.discord.widgets.home

import android.content.Context
import android.content.res.ColorStateList
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuItemCompat
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.isArchivedThread
import com.discord.api.channel.isDMStatusVisible
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.channel.isThread
import com.discord.api.channel.isVoiceChannel
import com.discord.databinding.WidgetHomeBinding
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreStream
import com.discord.utilities.channel.guildChannelIcon
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.feature.HeaderCustomization
import com.discord.utilities.feature.HeaderCustomization.Action
import com.discord.utilities.feature.HeaderCustomization.Entry
import com.discord.utilities.feature.HeaderCustomization.Identity
import com.discord.utilities.feature.HeaderCustomization.Surface
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.toolbar.positionUnreadCountView
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.channels.invite.GroupInviteFriendsSheet
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.friends.WidgetFriendsAdd
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.search.WidgetSearch
import com.discord.widgets.user.calls.PrivateCallLauncher

object WidgetHomeHeaderManager {
    private const val ANALYTICS_SOURCE = "Toolbar"

    data class HeaderData(
        val title: CharSequence?,
        val subtitle: CharSequence? = null,
        @DrawableRes val drawableRes: Int? = null,
        val leftButtonIsBack: Boolean = false,
        @DrawableRes val trailingDrawable: Int? = null,
        val avatarUrl: String? = null,
    )

    internal data class MenuState(
        val search: Boolean = false,
        val sidePanel: Boolean = false,
        val startCall: Boolean = false,
        val startVideoCall: Boolean = false,
        val stopCall: Boolean = false,
        val pins: Boolean = false,
    )

    fun configure(widgetHome: WidgetHome, model: WidgetHomeModel, binding: WidgetHomeBinding) {
        val channel = model.channel
        widgetHome.lockCloseRightPanel(
            channel == null || (!channel.isPrivate() && !channel.isGuildTextyChannel()) || channel.isArchivedThread(),
        )

        val toolbarContainer = when (channel?.type) {
            Channel.DIRECTORY -> binding.root.findViewById<View>(R.id.widget_home_panel_directory)
            Channel.GUILD_FORUM, Channel.GUILD_MEDIA ->
                binding.root.findViewById<View>(R.id.widget_forum_channel_list)
            else -> binding.root.findViewById<View>(R.id.widget_home_panel_center_chat)
        }
        widgetHome.bindToolbar(toolbarContainer)
        widgetHome.setActionBarTitleLayoutExpandedTappableArea()

        val context = widgetHome.context
        if (context != null) {
            val header = model.getHeaderData(context)
            widgetHome.setActionBarDisplayHomeAsUpEnabled(
                showHomeAsUp = true,
                iconRes = if (header.leftButtonIsBack) null else R.drawable.ic_menu_24dp,
                iconAccessibilityLabel = if (header.leftButtonIsBack) R.string.back else R.string.toggle_drawer,
            )
            widgetHome.setActionBarTitle(header.title, header.drawableRes, header.trailingDrawable)
            widgetHome.actionBarTitleLayout?.setAvatar(header.avatarUrl)
            widgetHome.setActionBarSubtitle(header.subtitle)
            widgetHome.appActivity?.toolbar?.setNavigationOnClickListener { widgetHome.handleCenterPanelBack() }
        }

        widgetHome.setActionBarTitleClick(View.OnClickListener {
            if (channel?.type in TITLE_OPENS_SIDE_PANEL_TYPES) widgetHome.getPanelLayout().openEndPanel()
        })

        val toolbar = widgetHome.setActionBarOptionsMenu(
            R.menu.menu_chat_toolbar,
            onSelected = { handleMenuItem(widgetHome, model, it) },
            onConfigured = { configureMenu(model, widgetHome.requireContext(), it) },
        )

        val surface = if (channel?.isPrivate() == true) Surface.DM else Surface.CHANNEL
        val custom = FeatureFlags.headerCustomizationsEnabled()
        val profileVisible = !custom || HeaderCustomization.identityVisible(surface, Identity.AVATAR)
        widgetHome.actionBarTitleLayout?.setPresence(
            model.dmPresence,
            headerStatusVisible(
                featureEnabled = FeatureFlags.chatHeaderStatusIndicatorsEnabled(),
                customizationsEnabled = custom,
                profileVisible = profileVisible,
                statusVisible = HeaderCustomization.identityVisible(surface, Identity.STATUS),
                channelStatusVisible = channel?.isDMStatusVisible(model.dmPresence) == true,
            ),
        )
        binding.root.findViewById<TextView>(R.id.home_toolbar_unread_count)?.let { unread ->
            unread.setTextAndVisibilityBy(unreadCountText(model.unreadCount))
            toolbar?.positionUnreadCountView(unread)
        }
        widgetHome.actionBarTitleLayout?.setIdentityVisibility(
            showIcon = !custom || HeaderCustomization.identityVisible(surface, Identity.AVATAR),
            showTitle = !custom || HeaderCustomization.identityVisible(surface, Identity.TITLE),
        )
    }

    private fun WidgetHomeModel.getHeaderData(context: Context): HeaderData = when (val selected = selectedChannel) {
        is StoreChannelsSelected.ResolvedSelectedChannel.Channel -> when (val selectedChannel = channel) {
            null -> HeaderData(context.getString(R.string.channels_unavailable_title))
            else -> when (selectedChannel.type) {
                Channel.GROUP_DM -> {
                    val icon = IconUtils.getForChannel(selectedChannel, size = 24)
                    HeaderData(
                        selectedChannel.getDisplayNameOrDefault(context, includePrefix = false),
                        drawableRes = R.drawable.ic_group_message_header.takeIf { icon == null },
                        avatarUrl = icon,
                    )
                }
                Channel.DM -> {
                    val avatar = selectedChannel.getDMRecipient()?.let { IconUtils.getForUser(it, size = 24) }
                    HeaderData(
                        selectedChannel.getDisplayNameOrDefault(context, includePrefix = false),
                        drawableRes = R.drawable.ic_direct_message_header.takeIf { avatar == null },
                        avatarUrl = avatar,
                    )
                }
                Channel.DIRECTORY -> HeaderData(
                    selectedChannel.getDisplayNameOrDefault(context, includePrefix = false),
                    drawableRes = R.drawable.ic_hub_24dp,
                )
                else -> if (selectedChannel.id > 0L) {
                    val resolvedParent = parentChannel
                        ?: StoreStream.getChannels().findChannelById(selectedChannel.parentId)
                    val isForumPost = selectedChannel.isGuildForumPost(resolvedParent) ||
                        StoreStream.getThreadsActive().getAllForumThreadsById().containsKey(selectedChannel.id)
                    HeaderData(
                        title = selectedChannel.getDisplayNameOrDefault(context, includePrefix = false),
                        subtitle = resolvedParent?.getDisplayNameOrDefault(context, includePrefix = false),
                        drawableRes = if (isForumPost) {
                            R.drawable.ic_discord_chat_filled_tinted
                        } else {
                            guildChannelIcon(selectedChannel)
                        },
                        leftButtonIsBack = isForumPost || selected.peekParent != null || selectedChannel.isVoiceChannel(),
                    )
                } else {
                    HeaderData(null)
                }
            }
        }
        is StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft -> HeaderData(
            title = context.getString(R.string.create_thread),
            subtitle = selected.parentChannel.getDisplayNameOrDefault(context, includePrefix = false),
            leftButtonIsBack = true,
        )
        else -> HeaderData(null)
    }

    private fun configureMenu(model: WidgetHomeModel, context: Context, menu: Menu) {
        val channel = model.channel
        val visible = customizeMenu(
            menu,
            context,
            if (channel?.isPrivate() == true) Surface.DM else Surface.CHANNEL,
        )
        val state = menuState(
            channelType = channel?.type,
            isArchivedThread = channel?.isArchivedThread() == true,
            isSystemDm = channel?.isSystemDM() == true,
            isCallConnected = model.isCallConnected,
        )
        menu.setHeaderActions(
            state.search && visible[Action.SEARCH] != false,
            state.sidePanel && visible[Action.MEMBERS] != false,
        )
        menu.findItem(R.id.menu_chat_start_call).isVisible = state.startCall && visible[Action.VOICE] != false
        menu.findItem(R.id.menu_chat_start_video_call).isVisible = state.startVideoCall && visible[Action.VIDEO] != false
        menu.findItem(R.id.menu_chat_stop_call).isVisible = state.stopCall && visible[Action.VOICE] != false
        menu.findItem(R.id.menu_chat_pins).isVisible = state.pins && visible[Action.PINS] != false
    }

    internal fun customizeMenu(menu: Menu, context: Context, surface: Surface): Map<Action, Boolean> {
        if (!FeatureFlags.headerCustomizationsEnabled()) {
            return Action.entries.associateWith { it != Action.PINS }
        }
        return HeaderCustomization.get(surface).also { reorderHeaderActions(menu, context, it) }
            .associate { it.action to it.visible }
    }

    private fun reorderHeaderActions(menu: Menu, context: Context, layout: List<Entry>) {
        HEADER_ACTION_IDS.forEach(menu::removeItem)
        layout.forEachIndexed { index, entry ->
            val order = 100 + index * 10
            when (entry.action) {
                Action.VOICE -> {
                    menu.addHeaderAction(
                        context,
                        R.id.menu_chat_start_call,
                        order,
                        R.string.start_call,
                        R.drawable.ic_call_24dp,
                    )
                    menu.addHeaderAction(
                        context,
                        R.id.menu_chat_stop_call,
                        order + 1,
                        R.string.leave_call,
                        R.drawable.ic_call_disconnect_24dp,
                        tint = R.color.status_red,
                    )
                }
                Action.VIDEO -> menu.addHeaderAction(
                    context,
                    R.id.menu_chat_start_video_call,
                    order,
                    R.string.start_video_call,
                    R.drawable.ic_videocam_white_24dp,
                )
                Action.SEARCH -> menu.addHeaderAction(
                    context,
                    R.id.menu_chat_search,
                    order,
                    R.string.search,
                    R.drawable.ic_search_white_24dp,
                    always = true,
                )
                Action.MEMBERS -> menu.addHeaderAction(
                    context,
                    R.id.menu_chat_side_panel,
                    order,
                    R.string.members,
                    R.drawable.ic_members_24dp,
                    always = true,
                )
                Action.PINS -> menu.addHeaderAction(
                    context,
                    R.id.menu_chat_pins,
                    order,
                    R.string.pinned_messages,
                    R.drawable.ic_pin_24dp,
                    always = true,
                )
            }
        }
    }

    private fun Menu.addHeaderAction(
        context: Context,
        id: Int,
        order: Int,
        title: Int,
        drawable: Int,
        tint: Int? = null,
        always: Boolean = false,
    ) {
        val item = add(Menu.NONE, id, order, title).setIcon(drawable)
        item.setShowAsAction(if (always) MenuItem.SHOW_AS_ACTION_ALWAYS else MenuItem.SHOW_AS_ACTION_IF_ROOM)
        MenuItemCompat.setIconTintList(
            item,
            ColorStateList.valueOf(
                tint?.let { ColorCompat.getColor(context, it) }
                    ?: ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal),
            ),
        )
    }

    internal fun menuState(
        channelType: Int?,
        isArchivedThread: Boolean = false,
        isSystemDm: Boolean = false,
        isCallConnected: Boolean = false,
    ): MenuState {
        // The bar carries search and members only. Pins and the thread browser both live in the
        // channel sidebar, so dropping them here orphans nothing, and leaving room for two icons is
        // what stops search being pushed into an overflow that is never shown.
        val header = when (channelType) {
            Channel.DM, Channel.GROUP_DM -> MenuState(search = true, sidePanel = true, pins = true)
            Channel.DIRECTORY -> MenuState(search = true, sidePanel = true)
            Channel.GUILD_TEXT, Channel.GUILD_ANNOUNCEMENT ->
                MenuState(search = true, sidePanel = true, pins = true)
            Channel.GUILD_FORUM, Channel.GUILD_MEDIA -> MenuState(search = true, sidePanel = true)
            Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD -> MenuState(
                search = true,
                sidePanel = !isArchivedThread,
                pins = true,
            )
            else -> MenuState()
        }
        val privateChannel = channelType == Channel.DM || channelType == Channel.GROUP_DM
        return header.copy(
            startCall = !isCallConnected && privateChannel && !isSystemDm,
            startVideoCall = !isCallConnected && privateChannel && !isSystemDm,
            stopCall = isCallConnected && !isSystemDm,
        )
    }

    private fun Menu.setHeaderActions(search: Boolean, sidePanel: Boolean) {
        findItem(R.id.menu_chat_search).isVisible = search
        findItem(R.id.menu_chat_side_panel).isVisible = sidePanel
    }

    private fun handleMenuItem(widgetHome: WidgetHome, model: WidgetHomeModel, item: MenuItem) {
        val context = widgetHome.context ?: return
        val launcher = PrivateCallLauncher(
            widgetHome,
            widgetHome,
            context,
            widgetHome.parentFragmentManager,
        )
        when (item.itemId) {
            R.id.menu_chat_add_friend -> WidgetFriendsAdd.show(context, source = ANALYTICS_SOURCE)
            // With no channel open the header belongs to the DM list, where search spans every
            // private channel rather than one of them.
            R.id.menu_chat_search -> when {
                model.channel == null -> WidgetSearch.launchForDms(context)
                model.channel.isPrivate() -> WidgetSearch.launchForChannel(model.channelId, context)
                model.channel.isGuildTextyChannel() -> WidgetSearch.launchForGuild(model.channel.guildId, context)
                else -> Unit
            }
            R.id.menu_chat_side_panel -> widgetHome.getPanelLayout().openEndPanel()
            R.id.menu_chat_pins -> WidgetChannelPinnedMessages.show(context, model.channelId)
            R.id.menu_chat_start_call -> launcher.launchVoiceCall(model.channelId)
            R.id.menu_chat_start_group -> inviteToChannel(widgetHome, model.channel)
            R.id.menu_chat_start_video_call -> launcher.launchVideoCall(model.channelId)
            R.id.menu_chat_stop_call -> StoreStream.getVoiceChannelSelected().clear()
        }
        widgetHome.hideKeyboard()
    }

    private fun inviteToChannel(widgetHome: WidgetHome, channel: Channel?) {
        channel ?: return
        if (channel.guildId != 0L) {
            WidgetGuildInviteShare.launch(
                context = widgetHome.requireContext(),
                fragmentManager = widgetHome.childFragmentManager,
                guildId = channel.guildId,
                channelId = channel.id,
                source = ANALYTICS_SOURCE,
            )
        } else {
            GroupInviteFriendsSheet.show(widgetHome.childFragmentManager, channel.id, ANALYTICS_SOURCE)
        }
    }

    internal fun unreadCountText(unreadCount: Int): String? = unreadCount.takeIf { it > 0 }?.toString()

    internal fun headerStatusVisible(
        featureEnabled: Boolean,
        customizationsEnabled: Boolean,
        profileVisible: Boolean,
        statusVisible: Boolean,
        channelStatusVisible: Boolean,
    ) = featureEnabled && profileVisible && channelStatusVisible &&
        (!customizationsEnabled || statusVisible)

    private val TITLE_OPENS_SIDE_PANEL_TYPES = setOf(
        Channel.DM,
        Channel.GROUP_DM,
        Channel.GUILD_ANNOUNCEMENT,
        Channel.GUILD_FORUM,
        Channel.GUILD_MEDIA,
        Channel.GUILD_TEXT,
    )

    private val HEADER_ACTION_IDS = intArrayOf(
        R.id.menu_chat_start_call,
        R.id.menu_chat_stop_call,
        R.id.menu_chat_start_video_call,
        R.id.menu_chat_pins,
        R.id.menu_chat_search,
        R.id.menu_chat_side_panel,
    )

}
