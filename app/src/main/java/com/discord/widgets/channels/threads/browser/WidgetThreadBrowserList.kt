package com.discord.widgets.channels.threads.browser

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.utilities.channel.ChannelSelector
import com.discord.widgets.channels.list.WidgetChannelsListItemThreadActions
import rx.Subscription

/**
 * Reference `ThreadList`: every thread in one scrolling list, with older threads paged in as it is
 * scrolled rather than living behind a second tab.
 */
class WidgetThreadBrowserList : AppFragment(R.layout.widget_thread_browser_list) {
    private val guildId get() = arguments?.getLong(EXTRA_GUILD_ID) ?: 0
    private val channelId get() = arguments?.getLong(EXTRA_CHANNEL_ID) ?: 0
    private lateinit var viewModel: WidgetThreadBrowserListViewModel
    private lateinit var adapter: WidgetThreadBrowserAdapter
    private lateinit var empty: View
    private lateinit var create: View
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            viewModelStore,
            factory { WidgetThreadBrowserListViewModel(guildId, channelId) },
        ).get(WidgetThreadBrowserListViewModel::class.java)

        adapter = WidgetThreadBrowserAdapter(
            onOpenThread = {
                ChannelSelector.getInstance().selectChannel(it)
                requireActivity().onBackPressed()
            },
            onThreadSettings = { WidgetChannelsListItemThreadActions.show(parentFragmentManager, it.id) },
        )
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        val layoutManager = LinearLayoutManager(requireContext())
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val remaining = layoutManager.itemCount - layoutManager.findLastVisibleItemPosition()
                if (remaining <= LOAD_MORE_THRESHOLD) viewModel.maybeLoadMore()
            }
        })

        empty = view.findViewById(R.id.empty_view)
        create = view.findViewById(R.id.thread_browser_empty_create_button)
        view.findViewById<TextView>(R.id.thread_browser_empty_heading)
            .setText(R.string.thread_browser_empty_state_header)
        view.findViewById<TextView>(R.id.thread_browser_empty_subheading)
            .setText(R.string.thread_browser_empty_state_subtext_redesign)
        create.setOnClickListener { openThreadCreation() }
    }

    private fun openThreadCreation() {
        ChannelSelector.getInstance().openCreateThread(
            guildId,
            channelId,
            startThreadLocation = "Thread Browser Empty State",
        )
        requireActivity().finish()
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

    private fun render(state: WidgetThreadBrowserListViewModel.ViewState) {
        adapter.submitList(state.listItems)
        empty.visibility = if (state.isEmpty) View.VISIBLE else View.INVISIBLE
        create.visibility = if (state.canCreateThread) View.VISIBLE else View.GONE
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val LOAD_MORE_THRESHOLD = 5

        fun create(guildId: Long, channelId: Long) = WidgetThreadBrowserList().apply {
            arguments = Bundle().apply {
                putLong(EXTRA_GUILD_ID, guildId)
                putLong(EXTRA_CHANNEL_ID, channelId)
            }
        }

        private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return create() as T
            }
        }
    }
}
