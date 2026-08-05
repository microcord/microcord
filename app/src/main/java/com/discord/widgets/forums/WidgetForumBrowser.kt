package com.discord.widgets.forums

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.api.channel.Channel
import com.discord.databinding.WidgetForumBrowserBinding
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.widgets.home.WidgetHomePanelNsfw
import com.discord.widgets.home.WidgetHome

class WidgetForumBrowser : AppFragment(R.layout.widget_forum_browser) {
    private val viewModel: WidgetForumBrowserViewModel by appViewModels { WidgetForumBrowserViewModel() }
    private var _binding: WidgetForumBrowserBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val adapterItems = ArrayList<ForumBrowserItem>()
    private lateinit var adapter: WidgetForumBrowserAdapter
    private lateinit var layoutManager: ForumBrowserLayoutManager
    private lateinit var scrollListener: ForumBrowserScrollListener
    private lateinit var nsfwPanel: WidgetHomePanelNsfw
    private var panelNsfwHidden = true

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetForumBrowserBinding.bind(view)
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

        adapter = WidgetForumBrowserAdapter(::openForumPost)
        layoutManager = ForumBrowserLayoutManager(binding.recyclerView)
        scrollListener = ForumBrowserScrollListener()
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(adapter.itemDecoration)
        binding.recyclerView.addOnScrollListener(scrollListener)

        WidgetForumGuidelinesBottomSheet.registerForResult(this, CLOSE_GUIDELINES_CREATE) { guildId, channelId ->
            openCreatePostScreen(guildId, channelId)
        }
        parentFragmentManager.setFragmentResultListener(
            WidgetForumSortBottomSheet.RESULT_KEY,
            this,
        ) { _, result ->
            val channelId = result.getLong(WidgetForumSortBottomSheet.ARG_CHANNEL_ID)
            (viewModel.viewState as? WidgetForumBrowserViewModel.ViewState.Loaded)
                ?.takeIf { it.channelId == channelId }
                ?.let(::configure)
        }
        val home = parentFragment as? WidgetHome
        val toolbar = if (home == null) {
            setActionBarDisplayHomeAsUpEnabled()
        } else {
            setActionBarDisplayHomeAsUpEnabled(
                iconRes = R.drawable.ic_menu_24dp,
                iconAccessibilityLabel = R.string.toggle_drawer,
            )
        }
        toolbar?.setNavigationOnClickListener {
            if (home == null) requireActivity().onBackPressedDispatcher.onBackPressed()
            else home.handleCenterPanelBack()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeEvents().ui(this).appSubscribe(javaClass) { event ->
            if (event is WidgetForumBrowserViewModel.Event.ScrollToTop) binding.recyclerView.scrollToPosition(0)
        }
        viewModel.observeViewState().ui(this).appSubscribe(javaClass, onNext = ::configure)
    }

    private fun configure(state: WidgetForumBrowserViewModel.ViewState) {
        bindToolbar()
        nsfwPanel.configureUI(
            state.nsfwPanelState.guildId,
            state.nsfwPanelState.isChannelNsfw,
            state.nsfwPanelState.isNsfwUnconsented,
            state.nsfwPanelState.nsfwAllowed,
            binding.nsfwView,
            onToggleNsfw = { shown ->
            panelNsfwHidden = !shown
            (viewModel.viewState as? WidgetForumBrowserViewModel.ViewState.Loaded)?.let(::configureButtons)
            },
        )
        when (state) {
            is WidgetForumBrowserViewModel.ViewState.Loading -> {
                binding.loadingView.root.visibility = View.VISIBLE
                binding.emptyView.root.visibility = View.GONE
                binding.recyclerView.visibility = View.GONE
                binding.createPost.visibility = View.GONE
                binding.viewGuidelines.visibility = View.GONE
                binding.forumDisplayOptions.visibility = View.GONE
                adapterItems.clear()
                adapter.setData(emptyList())
            }
            is WidgetForumBrowserViewModel.ViewState.Loaded -> {
                binding.loadingView.root.visibility = View.GONE
                setActionBarTitle(
                    state.channelName,
                    leadingDrawableRes = if (state.isMediaChannel) {
                        R.drawable.ic_image_library_24dp
                    } else {
                        R.drawable.ic_discord_forum_filled_tinted
                    },
                )
                actionBarTitleLayout?.findViewById<ImageView>(R.id.toolbar_icon)?.imageTintList =
                    ColorStateList.valueOf(ColorCompat.getThemedColor(requireContext(), R.attr.colorInteractiveNormal))
                configureButtons(state)
                val items = sortForumItems(state.listItems, ForumUtils.getSortOrder(state.channelId))
                if (items.isEmpty()) {
                    binding.emptyView.root.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                    binding.emptyView.forumBrowserEmptySubheading.text = getString(R.string.forum_empty_state_description)
                        .replace("{channelName}", state.channelName)
                    adapterItems.clear()
                    adapter.setData(emptyList())
                } else {
                    binding.emptyView.root.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    adapterItems.clear()
                    adapterItems.addAll(items)
                    adapter.setData(items)
                    configureScrolling(state)
                }
            }
        }
    }

    private fun configureButtons(state: WidgetForumBrowserViewModel.ViewState.Loaded) {
        binding.createPost.visibility = if (state.canCreateForumChannelPosts && panelNsfwHidden) View.VISIBLE else View.GONE
        binding.viewGuidelines.visibility = if (state.channelTopic.isNotBlank() && panelNsfwHidden) View.VISIBLE else View.GONE
        binding.forumDisplayOptions.visibility = if (state.isMediaChannel && panelNsfwHidden) View.VISIBLE else View.GONE
        binding.forumSortOptions.setOnClickListener {
            WidgetForumSortBottomSheet.show(parentFragmentManager, state.channelId)
        }
        binding.createPost.setOnClickListener {
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
        binding.viewGuidelines.setOnClickListener {
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
        if (parentFragment !is WidgetHome) requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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
