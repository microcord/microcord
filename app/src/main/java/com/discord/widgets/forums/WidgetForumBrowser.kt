package com.discord.widgets.forums

import android.content.Context
import android.view.View
import android.view.ViewStub
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.api.channel.Channel
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.list.WidgetChannelsListItemThreadActions
import com.discord.widgets.home.WidgetHomePanelNsfw
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

class WidgetForumBrowser : AppFragment(R.layout.widget_forum_browser) {
    private val viewModel: WidgetForumBrowserViewModel by appViewModels { WidgetForumBrowserViewModel() }
    private val adapterItems = ArrayList<ForumBrowserItem>()
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: WidgetForumBrowserAdapter
    private lateinit var layoutManager: ForumBrowserLayoutManager
    private lateinit var scrollListener: ForumBrowserScrollListener
    private lateinit var loading: View
    private lateinit var empty: View
    private lateinit var emptyDescription: TextView
    private lateinit var createPost: ExtendedFloatingActionButton
    private lateinit var viewGuidelines: FloatingActionButton
    private lateinit var nsfwStub: ViewStub
    private lateinit var nsfwPanel: WidgetHomePanelNsfw
    private var panelNsfwHidden = true

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        recycler = view.findViewById(R.id.recycler_view)
        loading = view.findViewById(R.id.loading_view)
        empty = view.findViewById(R.id.empty_view)
        emptyDescription = view.findViewById(R.id.forum_browser_empty_subheading)
        createPost = view.findViewById(R.id.create_post)
        viewGuidelines = view.findViewById(R.id.view_guidelines)
        nsfwStub = view.findViewById(R.id.nsfw_view)
        nsfwPanel = WidgetHomePanelNsfw(this)
        WidgetHomePanelNsfw.onGuildNsfwGateAllowed = ForumUtils::allowGuildNsfw
        WidgetHomePanelNsfw.onGuildNsfwGateDenied = { guildId ->
            StoreStream.getChannels().observeDefaultChannel(guildId).takeSingleUntilTimeout()
                .appSubscribe(javaClass) { channel ->
                    if (channel != null && !channel.nsfw) {
                        ChannelSelector.getInstance().selectChannel(channel)
                    } else {
                        ChannelSelector.getInstance().selectChannel(0L, 0L)
                    }
                }
        }

        adapter = WidgetForumBrowserAdapter(::openForumPost, ::openForumPostOptions)
        layoutManager = ForumBrowserLayoutManager(recycler)
        scrollListener = ForumBrowserScrollListener()
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addItemDecoration(adapter.itemDecoration)
        recycler.addOnScrollListener(scrollListener)

        WidgetForumGuidelinesBottomSheet.registerForResult(this, CLOSE_GUIDELINES_CREATE) { guildId, channelId ->
            openCreatePostScreen(guildId, channelId)
        }
        setActionBarDisplayHomeAsUpEnabled()
        appActivity?.toolbar?.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeEvents().ui(this).appSubscribe(javaClass) { event ->
            if (event is WidgetForumBrowserViewModel.Event.ScrollToTop) recycler.scrollToPosition(0)
        }
        viewModel.observeViewState().ui(this).appSubscribe(javaClass, onNext = ::configure)
    }

    private fun configure(state: WidgetForumBrowserViewModel.ViewState) {
        nsfwPanel.configureUI(
            state.nsfwPanelState.guildId,
            state.nsfwPanelState.isChannelNsfw,
            state.nsfwPanelState.isNsfwUnconsented,
            state.nsfwPanelState.nsfwAllowed,
            nsfwStub,
            onToggleNsfw = { shown ->
            panelNsfwHidden = !shown
            (viewModel.viewState as? WidgetForumBrowserViewModel.ViewState.Loaded)?.let(::configureButtons)
            },
        )
        when (state) {
            is WidgetForumBrowserViewModel.ViewState.Loading -> {
                loading.visibility = View.VISIBLE
                empty.visibility = View.GONE
                recycler.visibility = View.GONE
                createPost.visibility = View.GONE
                viewGuidelines.visibility = View.GONE
                adapterItems.clear()
                adapter.setData(emptyList())
            }
            is WidgetForumBrowserViewModel.ViewState.Loaded -> {
                loading.visibility = View.GONE
                setActionBarTitle(state.channelName)
                configureButtons(state)
                if (state.listItems.isEmpty()) {
                    empty.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                    emptyDescription.text = getString(R.string.forum_empty_state_description)
                        .replace("{channelName}", state.channelName)
                    adapterItems.clear()
                } else {
                    empty.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                    adapterItems.clear()
                    adapterItems.addAll(state.listItems)
                    adapter.setData(state.listItems)
                    configureScrolling(state)
                }
            }
        }
    }

    private fun configureButtons(state: WidgetForumBrowserViewModel.ViewState.Loaded) {
        createPost.visibility = if (state.canCreateForumChannelPosts && panelNsfwHidden) View.VISIBLE else View.GONE
        viewGuidelines.visibility = if (state.channelTopic.isNotBlank() && panelNsfwHidden) View.VISIBLE else View.GONE
        createPost.setOnClickListener {
            if (state.channelTopic.isNotBlank() && !ForumGuidelinesManager.hasSeenGuidelines(state.channelId)) {
                WidgetForumGuidelinesBottomSheet.show(
                    parentFragmentManager,
                    state.guildId,
                    state.channelId,
                    CLOSE_GUIDELINES_CREATE,
                )
            } else {
                openCreatePostScreen(state.guildId, state.channelId)
            }
        }
        viewGuidelines.setOnClickListener {
            WidgetForumGuidelinesBottomSheet.show(
                parentFragmentManager,
                state.guildId,
                state.channelId,
                CLOSE_GUIDELINES_NOOP,
            )
        }
    }

    private fun configureScrolling(state: WidgetForumBrowserViewModel.ViewState.Loaded) {
        layoutManager.setOnLayoutCompletedCallback { onLayoutCompleted(state) }
        scrollListener.setOnScrollCallback { enqueueVisibleFirstMessages(state) }
        scrollListener.setOnScrollEndCallback { viewModel.maybeFetchOlderPosts(state.channelId) }
        scrollListener.setOnScrollStateChangedCallback { scrollState ->
            enqueueVisibleFirstMessages(state)
            if (scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                viewModel.maybeFetchForumPostFirstMessages(state.channelId)
            }
        }
    }

    private fun onLayoutCompleted(state: WidgetForumBrowserViewModel.ViewState.Loaded) {
        if (adapterItems.filterIsInstance<ForumBrowserItem.PostItem>().any { it.post.isActivePost }) {
            enqueueVisibleFirstMessages(state)
            viewModel.maybeFetchForumPostFirstMessages(state.channelId, initial = true)
            viewModel.requestForumUnreads(state.channelId, state.guildId)
        }
    }

    private fun enqueueVisibleFirstMessages(state: WidgetForumBrowserViewModel.ViewState.Loaded) {
        val first = layoutManager.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: return
        val last = layoutManager.findLastVisibleItemPosition().takeIf { it >= 0 } ?: return
        val from = first / FETCH_WINDOW * FETCH_WINDOW
        val to = minOf(adapterItems.lastIndex, (last / FETCH_WINDOW + 1) * FETCH_WINDOW - 1)
        if (from > to) return
        for (index in from..to) {
            val item = adapterItems[index] as? ForumBrowserItem.PostItem ?: continue
            if (item.post.isActivePost) {
                viewModel.enqueueForumPostFirstMessageFetch(state.channelId, item.post.channel.id)
            }
        }
    }

    private fun openCreatePostScreen(guildId: Long, channelId: Long) {
        ChannelSelector.getInstance().openCreateThread(guildId, channelId, startThreadLocation = "Thread Browser Toolbar")
    }

    private fun openForumPost(channel: Channel) {
        ChannelSelector.getInstance().selectChannel(
            channel,
            peekParent = channel.parentId,
            analyticsLocation = SelectedChannelAnalyticsLocation.THREAD_BROWSER,
        )
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun openForumPostOptions(channel: Channel) {
        WidgetChannelsListItemThreadActions.show(parentFragmentManager, channel.id)
    }

    companion object {
        private const val FETCH_WINDOW = 10
        private const val CLOSE_GUIDELINES_CREATE = "CLOSE_FORUM_GUIDELINES_REQUEST_KEY"
        private const val CLOSE_GUIDELINES_NOOP = "CLOSE_FORUM_GUIDELINES_NOOP_REQUEST_KEY"

        @JvmStatic
        fun show(context: Context?) {
            context?.let { AppScreenRouter.launch(it, WidgetForumBrowser::class.java) }
        }
    }
}
