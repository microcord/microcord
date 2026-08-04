package com.discord.widgets.channels

import android.view.View
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.views.channelsidebar.GuildChannelSideBarActionsView
import com.discord.views.channelsidebar.PrivateChannelSideBarActionsView
import com.discord.widgets.channels.WidgetChannelSidebarActionsViewModel.ViewState
import com.discord.widgets.channels.settings.WidgetTextChannelSettings
import com.discord.widgets.channels.settings.WidgetThreadSettings
import com.discord.widgets.channels.threads.browser.WidgetThreadBrowser
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.search.WidgetSearch
import com.discord.widgets.settings.WidgetMuteSettingsSheet
import com.discord.widgets.user.calls.PrivateCallLauncher

/**
 * The action row at the top of the right-hand panel.
 *
 * Guild channels and private channels get two different views, only one of which is ever visible.
 * On a wide screen a guild channel offers Threads in the first slot and Search moves into the
 * toolbar; on a narrow screen — and always for forum channels and forum posts, which have no thread
 * browser of their own — the first slot is Search.
 */
class WidgetChannelSidebarActions : AppFragment(R.layout.widget_channel_sidebar_actions) {

    private val viewModel: WidgetChannelSidebarActionsViewModel by appViewModels {
        WidgetChannelSidebarActionsViewModel()
    }

    private lateinit var guildView: GuildChannelSideBarActionsView
    private lateinit var privateView: PrivateChannelSideBarActionsView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        guildView = view.findViewById(R.id.widget_channel_sidebar_actions_guild_view)
        privateView = view.findViewById(R.id.widget_channel_sidebar_actions_private_view)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetChannelSidebarActions::class.java) { configureUI(it) }
    }

    private fun configureUI(viewState: ViewState) {
        when (viewState) {
            ViewState.Uninitialized -> view?.isVisible = false

            is ViewState.Private -> {
                view?.isVisible = true
                guildView.visibility = View.GONE
                privateView.visibility = View.VISIBLE
                privateView.configure(
                    onCallClicked = View.OnClickListener {
                        startPrivateCall(viewState.channelId, useVideo = false)
                    },
                    onVideoClicked = View.OnClickListener {
                        startPrivateCall(viewState.channelId, useVideo = true)
                    },
                    onNotificationsClicked = View.OnClickListener {
                        openNotificationSettings(viewState.channelId)
                    },
                    onSearchClicked = View.OnClickListener {
                        WidgetSearch.launchForChannel(viewState.channelId, requireContext())
                    },
                    channelIsMuted = viewState.isMuted,
                )
            }

            is ViewState.Guild -> {
                view?.isVisible = true
                privateView.visibility = View.GONE
                guildView.isVisible = !viewState.shouldHideChannelSidebar
                guildView.configure(
                    onSearchClicked = { WidgetSearch.launchForGuild(viewState.guildId, requireContext()) },
                    onThreadBrowserClicked = {
                        (parentFragment as? WidgetHome)?.getPanelLayout()?.closePanels()
                        WidgetThreadBrowser.show(
                            requireContext(),
                            viewState.guildId,
                            viewState.channelId,
                            THREAD_BROWSER_SOURCE,
                        )
                    },
                    onNotificationsClicked = { openNotificationSettings(viewState.channelId) },
                    onPinsClicked = { openPins(viewState.channelId) },
                    onSettingsClicked = {
                        if (viewState.isThread) WidgetThreadSettings.launch(viewState.channelId, requireContext())
                        else WidgetTextChannelSettings.launch(viewState.channelId, requireContext())
                    },
                    hasUnseenPins = viewState.hasUnreadPins,
                    channelIsMuted = viewState.isMuted,
                    disablePins = viewState.disablePins,
                    // Forums have no thread browser, so they always keep Search in the first slot.
                    showSearch = isSmallScreen() || viewState.isGuildForumPost || viewState.isGuildForumChannel,
                )
            }
        }
    }

    private fun openPins(channelId: Long) {
        WidgetChannelPinnedMessages.show(requireContext(), channelId)
    }

    private fun openNotificationSettings(channelId: Long) {
        val installed = onNotifications
        if (installed != null) installed(channelId)
        else WidgetMuteSettingsSheet.showForChannel(channelId, parentFragmentManager)
    }

    private fun startPrivateCall(channelId: Long, useVideo: Boolean) {
        val launcher = PrivateCallLauncher(this, this, requireContext(), parentFragmentManager)
        if (useVideo) launcher.launchVideoCall(channelId) else launcher.launchVoiceCall(channelId)
    }

    /**
     * Reproduces `DeviceUtils.isSmallScreen`, which has not been reconstructed: a screen at most
     * 360dp wide.
     */
    private fun isSmallScreen(): Boolean = with(resources.displayMetrics) {
        widthPixels <= SMALL_SCREEN_WIDTH_DP * density
    }

    companion object {
        private const val SMALL_SCREEN_WIDTH_DP = 360

        const val THREAD_BROWSER_SOURCE = "Channel Side Bar"

        /**
         * Host-supplied navigation seams, installed by `WidgetHome`.
         *
         * [onSearch] and [onNotifications] remain so a host can override the default route.
         */
        @JvmField var onSearch: ((Channel) -> Unit)? = null
        @JvmField var onNotifications: ((Long) -> Unit)? = null
    }
}
