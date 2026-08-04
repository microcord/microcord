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

class WidgetThreadBrowserArchived : AppFragment(R.layout.widget_thread_browser_archived) {
    private val guildId get() = arguments?.getLong(EXTRA_GUILD_ID) ?: 0
    private val channelId get() = arguments?.getLong(EXTRA_CHANNEL_ID) ?: 0
    private lateinit var viewModel: WidgetThreadBrowserArchivedViewModel
    private lateinit var adapter: WidgetThreadBrowserAdapter
    private lateinit var empty: View
    private lateinit var error: View
    private lateinit var create: View
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity().viewModelStore, factory {
            WidgetThreadBrowserArchivedViewModel(guildId, channelId)
        })[viewModelKey(), WidgetThreadBrowserArchivedViewModel::class.java]
        adapter = WidgetThreadBrowserAdapter(
            onOpenThread = {
                ChannelSelector.getInstance().selectChannel(it)
                requireActivity().onBackPressed()
            },
            onThreadSettings = { WidgetChannelsListItemThreadActions.show(parentFragmentManager, it.id) },
        )
        view.findViewById<RecyclerView>(R.id.recycler_view).apply {
            val manager = LinearLayoutManager(requireContext())
            layoutManager = manager
            adapter = this@WidgetThreadBrowserArchived.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (manager.findLastVisibleItemPosition() >= manager.itemCount - LOAD_MORE_DISTANCE) {
                        viewModel.maybeLoadMore()
                    }
                }
            })
        }
        empty = view.findViewById(R.id.empty_view)
        error = view.findViewById(R.id.thread_browser_error_overlay)
        create = view.findViewById(R.id.thread_browser_empty_create_button)
        view.findViewById<TextView>(R.id.thread_browser_empty_heading)
            .setText(R.string.thread_browser_empty_state_archived_header)
        create.setOnClickListener {
            ChannelSelector.getInstance().openCreateThread(
                guildId,
                channelId,
                startThreadLocation = "Thread Browser Empty State",
            )
            requireActivity().finish()
        }
        view.findViewById<View>(R.id.thread_browser_try_again).setOnClickListener {
            viewModel.maybeLoadMore(reload = true)
        }
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

    private fun render(state: WidgetThreadBrowserArchivedViewModel.ViewState) {
        adapter.submitList(state.listItems)
        error.visibility = if (state.isError) View.VISIBLE else View.GONE
        val hasThreads = state.listItems.any { it is WidgetThreadBrowserAdapter.Item.Thread }
        empty.visibility = if (!state.isError && !hasThreads && state.listItems.none { it is WidgetThreadBrowserAdapter.Item.Loading }) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        create.visibility = if (state.canCreateThread) View.VISIBLE else View.GONE
    }

    private fun viewModelKey() = "thread-browser-archived:$guildId:$channelId"

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val LOAD_MORE_DISTANCE = 8

        fun create(guildId: Long, channelId: Long) = WidgetThreadBrowserArchived().apply {
            arguments = Bundle().apply {
                putLong(EXTRA_GUILD_ID, guildId)
                putLong(EXTRA_CHANNEL_ID, channelId)
            }
        }

        internal fun key(guildId: Long, channelId: Long) = "thread-browser-archived:$guildId:$channelId"

        internal fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return create() as T
            }
        }
    }
}
