package com.discord.widgets.channels.threads.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.simple_pager.SimplePager
import com.google.android.material.tabs.TabLayout
import rx.Subscription

class WidgetThreadBrowser : AppFragment(R.layout.widget_thread_browser) {
    private val guildId get() = mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, 0)
    private val channelId get() = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, 0)
    private lateinit var viewModel: WidgetThreadBrowserViewModel
    private lateinit var pager: SimplePager
    private var browserState: WidgetThreadBrowserViewModel.ViewState.Browser? = null
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(viewModelStore, factory { WidgetThreadBrowserViewModel(guildId, channelId) })
            .get(WidgetThreadBrowserViewModel::class.java)
        pager = view.findViewById(R.id.thread_browser_view_pager)
        setActionBarTitle(R.string.thread_browser_title)
        setActionBarDisplayHomeAsUpEnabled()
    }

    override fun onStart() {
        super.onStart()
        subscription = viewModel.observeViewState().subscribe(::render)
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun render(state: WidgetThreadBrowserViewModel.ViewState) {
        when (state) {
            is WidgetThreadBrowserViewModel.ViewState.GoToChannel -> {
                ChannelSelector.getInstance().selectChannel(
                    state.channel,
                    analyticsLocation = SelectedChannelAnalyticsLocation.THREAD_BROWSER,
                )
                requireActivity().onBackPressed()
            }
            is WidgetThreadBrowserViewModel.ViewState.Browser -> {
                browserState = state
                if (pager.adapter == null) {
                    val pages = buildList {
                        add(SimplePager.Item(getString(R.string.thread_browser_active)) {
                            WidgetThreadBrowserActive.create(guildId, channelId)
                        })
                        if (state.canViewArchivedThreads) add(SimplePager.Item(getString(R.string.thread_browser_archived)) {
                            WidgetThreadBrowserArchived.create(guildId, channelId)
                        })
                    }
                    pager.adapter = SimplePager.Adapter(childFragmentManager, *pages.toTypedArray())
                    pager.setTabLayout(view?.findViewById<TabLayout>(R.id.action_bar_tabs))
                    pager.addOnPageChangeListener(object : androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
                        override fun onPageSelected(position: Int) = configureMenu(position)
                    })
                }
                if (state.isForumChannel) setActionBarTitle("#${state.channelName.orEmpty()}")
                else setActionBarSubtitle(state.channelName?.let { "#$it" })
                configureMenu(pager.currentItem)
            }
        }
    }

    private fun configureMenu(page: Int) {
        val state = browserState ?: return
        setActionBarOptionsMenu(
            R.menu.menu_thread_browser,
            onSelected = { item ->
                when (item.itemId) {
                    R.id.menu_thread_browser_create_thread -> {
                        ChannelSelector.getInstance()
                            .openCreateThread(guildId, channelId, startThreadLocation = "Thread Browser Toolbar")
                        requireActivity().finish()
                    }
                    R.id.menu_thread_browser_filters -> WidgetThreadBrowserFilterSheet.show(
                        parentFragmentManager,
                        guildId,
                        channelId,
                    )
                }
            },
            onConfigured = { menu ->
                menu.findItem(R.id.menu_thread_browser_filters)?.isVisible = page == ARCHIVED_PAGE
                menu.findItem(R.id.menu_thread_browser_create_thread)?.isVisible =
                    page == ACTIVE_PAGE && state.canCreateThread
            },
        )
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val ACTIVE_PAGE = 0
        private const val ARCHIVED_PAGE = 1

        fun show(context: Context, guildId: Long, channelId: Long, location: String) {
            AppScreenRouter.launch(context, WidgetThreadBrowser::class.java, Intent().apply {
                putExtra(EXTRA_GUILD_ID, guildId)
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra("location", location)
            })
        }

        private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return create() as T
            }
        }
    }
}
