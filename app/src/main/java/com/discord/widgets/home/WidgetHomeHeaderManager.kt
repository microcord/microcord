package com.discord.widgets.home

import android.content.Context
import android.content.res.Resources
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isArchivedThread
import com.discord.api.channel.isDMStatusVisible
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.channel.isThread
import com.discord.api.channel.isVoiceChannel
import com.discord.api.user.NsfwAllowance
import com.discord.databinding.WidgetHomeBinding
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreStream
import com.discord.utilities.channel.guildChannelIcon
import com.discord.utilities.toolbar.positionUnreadCountView
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.channels.WidgetGroupInviteFriends
import com.discord.widgets.channels.invite.GroupInviteFriendsSheet
import com.discord.widgets.channels.invite.GroupInviteFriendsSheetFeatureFlag
import com.discord.widgets.channels.threads.browser.WidgetThreadBrowser
import com.discord.widgets.friends.WidgetFriendsAdd
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.search.WidgetSearch
import com.discord.widgets.user.calls.PrivateCallLauncher

object WidgetHomeHeaderManager {
    private const val ANALYTICS_SOURCE = "Toolbar"
    private const val SMALL_SCREEN_WIDTH_DP = 360

    data class HeaderData(
        val title: CharSequence?,
        val subtitle: CharSequence? = null,
        @DrawableRes val drawableRes: Int? = null,
        val leftButtonIsBack: Boolean = false,
        @DrawableRes val trailingDrawable: Int? = null,
    )

    fun configure(widgetHome: WidgetHome, model: WidgetHomeModel, binding: WidgetHomeBinding) {
        val channel = model.channel
        widgetHome.lockCloseRightPanel(
            channel == null || (!channel.isPrivate() && !channel.isGuildTextyChannel()) || channel.isArchivedThread(),
        )

        val toolbarContainer = when (channel?.type) {
            Channel.DIRECTORY -> binding.root.findViewById<View>(R.id.widget_home_panel_directory)
            Channel.GUILD_FORUM -> binding.root.findViewById<View>(R.id.widget_forum_channel_list)
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
            widgetHome.setActionBarSubtitle(header.subtitle)
            widgetHome.appActivity?.toolbar?.setNavigationOnClickListener { widgetHome.handleCenterPanelBack() }
        }

        widgetHome.setActionBarTitleClick(View.OnClickListener {
            if (channel?.type in TITLE_OPENS_SIDE_PANEL_TYPES) widgetHome.getPanelLayout().openEndPanel()
        })

        val toolbar = widgetHome.setActionBarOptionsMenu(
            R.menu.menu_chat_toolbar,
            onSelected = { handleMenuItem(widgetHome, model, it) },
            onConfigured = { configureMenu(model, widgetHome.resources, it) },
        )

        widgetHome.actionBarTitleLayout?.presence?.apply {
            isVisible = channel?.isDMStatusVisible(model.dmPresence) == true
            setPresence(model.dmPresence)
        }
        binding.root.findViewById<TextView>(R.id.home_toolbar_unread_count)?.let { unread ->
            unread.setTextAndVisibilityBy(model.unreadCount.takeIf { it > 0 }?.toString())
            toolbar?.positionUnreadCountView(unread)
        }
        configureThreadBrowserIconBehavior(toolbar, model, channel, context)
    }

    private fun WidgetHomeModel.getHeaderData(context: Context): HeaderData = when (val selected = selectedChannel) {
        is StoreChannelsSelected.ResolvedSelectedChannel.Channel -> when (val selectedChannel = channel) {
            null -> HeaderData(context.getString(R.string.channels_unavailable_title))
            else -> when (selectedChannel.type) {
                Channel.GROUP_DM -> HeaderData(
                    selectedChannel.getDisplayNameOrDefault(context, includePrefix = false),
                    drawableRes = R.drawable.ic_group_message_header,
                )
                Channel.DM -> HeaderData(
                    selectedChannel.getDisplayNameOrDefault(context, includePrefix = false),
                    drawableRes = R.drawable.ic_direct_message_header,
                )
                Channel.DIRECTORY -> HeaderData(
                    selectedChannel.getDisplayNameOrDefault(context, includePrefix = false),
                    drawableRes = R.drawable.ic_hub_24dp,
                )
                else -> if (selectedChannel.id > 0L) {
                    HeaderData(
                        title = selectedChannel.getDisplayNameOrDefault(context, includePrefix = false),
                        subtitle = parentChannel?.getDisplayNameOrDefault(context, includePrefix = false),
                        drawableRes = guildChannelIcon(selectedChannel),
                        leftButtonIsBack = selected.peekParent != null || selectedChannel.isVoiceChannel(),
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

    private fun configureMenu(model: WidgetHomeModel, resources: Resources, menu: Menu) {
        val channel = model.channel
        val smallScreen = resources.displayMetrics.widthPixels <= SMALL_SCREEN_WIDTH_DP * resources.displayMetrics.density
        val showThreadBrowser = model.threadExperimentEnabled && model.threadCount > 0 &&
            !isChannelNsfwGated(model.isChannelNsfw, model.isNsfwUnConsented, model.nsfwAllowed)

        when (channel?.type) {
            Channel.DM, Channel.GROUP_DM -> menu.setHeaderActions(search = false, sidePanel = true, threads = false)
            Channel.GUILD_TEXT, Channel.GUILD_ANNOUNCEMENT, Channel.GUILD_FORUM -> menu.setHeaderActions(
                search = !smallScreen || !model.threadExperimentEnabled,
                sidePanel = true,
                threads = showThreadBrowser,
            )
            Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD -> menu.setHeaderActions(
                search = !smallScreen,
                sidePanel = channel.isArchivedThread().not(),
                threads = false,
            )
            Channel.DIRECTORY -> menu.setHeaderActions(search = false, sidePanel = true, threads = false)
        }

        val callablePrivateChannel = channel?.isPrivate() == true && !channel.isSystemDM()
        menu.findItem(R.id.menu_chat_start_call).isVisible = !model.isCallConnected && callablePrivateChannel
        menu.findItem(R.id.menu_chat_start_video_call).isVisible = !model.isCallConnected && callablePrivateChannel
        menu.findItem(R.id.menu_chat_stop_call).isVisible = model.isCallConnected && callablePrivateChannel
    }

    private fun Menu.setHeaderActions(search: Boolean, sidePanel: Boolean, threads: Boolean) {
        findItem(R.id.menu_chat_search).isVisible = search
        findItem(R.id.menu_chat_side_panel).isVisible = sidePanel
        findItem(R.id.menu_chat_thread_browser).isVisible = threads
    }

    private fun handleMenuItem(widgetHome: WidgetHome, model: WidgetHomeModel, item: MenuItem) {
        val context = widgetHome.context ?: return
        val launcher = PrivateCallLauncher(
            widgetHome,
            widgetHome,
            context,
            widgetHome.parentFragmentManager,
            StoreStream.getCalls(),
            StoreStream.getVoiceChannelSelected(),
            StoreStream.getMediaEngine(),
        )
        when (item.itemId) {
            R.id.menu_chat_add_friend -> WidgetFriendsAdd.show(context, source = ANALYTICS_SOURCE)
            R.id.menu_chat_search -> model.channel?.let { channel ->
                if (channel.isPrivate()) WidgetSearch.launchForChannel(model.channelId, context)
                else if (channel.isGuildTextyChannel()) WidgetSearch.launchForGuild(channel.guildId, context)
            }
            R.id.menu_chat_side_panel -> widgetHome.getPanelLayout().openEndPanel()
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
        } else if (GroupInviteFriendsSheetFeatureFlag.INSTANCE.isEnabled()) {
            GroupInviteFriendsSheet.show(widgetHome.childFragmentManager, channel.id, ANALYTICS_SOURCE)
        } else {
            WidgetGroupInviteFriends.launch(widgetHome.requireContext(), channel.id, ANALYTICS_SOURCE)
        }
    }

    private fun configureThreadBrowserIconBehavior(
        toolbar: Toolbar?,
        model: WidgetHomeModel,
        channel: Channel?,
        context: Context?,
    ) {
        channel ?: return
        val visible = !channel.isGuildForumChannel()
        val item = toolbar?.menu?.findItem(R.id.menu_chat_thread_browser)
        item?.actionView?.findViewById<TextView>(R.id.thread_browser_count)?.apply {
            text = minOf(99, model.threadCount).toString()
            isVisible = model.threadCount != 0 && visible
        }
        item?.actionView?.findViewById<View>(R.id.thread_browser_root)?.apply {
            isVisible = visible
            setOnClickListener {
                context ?: return@setOnClickListener
                WidgetThreadBrowser.show(
                    context,
                    channel.guildId,
                    if (channel.isThread()) channel.parentId else channel.id,
                    "Chat List Header",
                )
            }
        }
    }

    private fun isChannelNsfwGated(
        isChannelNsfw: Boolean,
        isNsfwUnConsented: Boolean,
        nsfwAllowed: NsfwAllowance?,
    ): Boolean = isChannelNsfw && (isNsfwUnConsented || nsfwAllowed == NsfwAllowance.DISALLOWED)

    private val TITLE_OPENS_SIDE_PANEL_TYPES = setOf(
        Channel.DM,
        Channel.GROUP_DM,
        Channel.GUILD_ANNOUNCEMENT,
        Channel.GUILD_FORUM,
        Channel.GUILD_TEXT,
    )
}
