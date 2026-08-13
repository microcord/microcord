package com.discord.widgets.discovery

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.discovery.DiscoverableGuild
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.databinding.WidgetGuildDiscoveryBinding
import com.discord.stores.JoinGuildSources
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.hubs.HubEmailArgs
import com.discord.widgets.hubs.toArgsIntent
import com.discord.widgets.hubs.WidgetHubEmailFlow

class WidgetGuildDiscovery : AppFragment(R.layout.widget_guild_discovery) {
    private var _binding: WidgetGuildDiscoveryBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: WidgetGuildDiscoveryViewModel by appViewModels {
        WidgetGuildDiscoveryViewModel()
    }
    private val adapter = GuildDiscoveryAdapter(
        onGuildClicked = ::onGuildClicked,
        onHubClicked = ::onHubClicked,
        onHubGuildClicked = ::onHubGuildClicked,
        onSearchClicked = {
            WidgetGuildDiscoverySearch.launch(requireContext(), viewModel.requireViewState().tab)
        },
    )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetGuildDiscoveryBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.global_discovery_title)
        binding.discoveryList.adapter = adapter
        binding.discoveryList.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    if (layout.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                        viewModel.loadMore()
                    }
                }
            },
        )
        bindPills()
    }

    private fun bindPills() {
        val row = binding.discoveryPills
        GuildDiscoveryTab.values().forEach { entry ->
            val pill = layoutInflater.inflate(R.layout.guild_discovery_tab, row, false) as TextView
            pill.setText(entry.tabTitle)
            pill.setOnClickListener { viewModel.selectTab(entry) }
            row.addView(pill)
        }
    }

    private fun renderPills(tab: GuildDiscoveryTab) {
        GuildDiscoveryTab.values().forEachIndexed { index, entry ->
            binding.discoveryPills.getChildAt(index)?.isSelected = entry == tab
        }
    }

    override fun onDestroyView() {
        binding.discoveryList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(WidgetGuildDiscovery::class.java) { configureUi(it) }
    }

    private fun configureUi(state: WidgetGuildDiscoveryViewModel.ViewState) {
        renderPills(state.tab)
        binding.discoveryLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        adapter.submitList(buildItems(state))
    }

    private fun buildItems(state: WidgetGuildDiscoveryViewModel.ViewState): List<GuildDiscoveryAdapter.Item> {
        val chrome = listOf<GuildDiscoveryAdapter.Item>(GuildDiscoveryAdapter.Item.Hero(state.tab))
        if (state.tab == GuildDiscoveryTab.HUBS) {
            return chrome + state.joinedHubs.map { GuildDiscoveryAdapter.Item.HubGuild(it) } +
                GuildDiscoveryAdapter.Item.HubCta(state.joinedHubs.isNotEmpty())
        }
        if (state.isLoading) return chrome
        if (state.hasFailed) {
            return chrome + GuildDiscoveryAdapter.Item.Empty(R.string.guild_discovery_search_error)
        }
        if (state.guilds.isEmpty()) {
            return chrome + GuildDiscoveryAdapter.Item.Empty(R.string.guild_discovery_search_empty_header)
        }
        val items = chrome.toMutableList()
        state.tab.sectionTitle?.let { items += GuildDiscoveryAdapter.Item.Header(it) }
        state.guilds.mapTo(items) { GuildDiscoveryAdapter.Item.Server(it) }
        return items
    }

    /** The reference lurks rather than joining, so the server opens read-only until you post. */
    private fun onGuildClicked(guild: DiscoverableGuild) {
        if (StoreStream.getGuilds().getGuild(guild.id) != null) {
            StoreStream.selectGuild(guild.id)
        } else {
            StoreStream.getLurking().startLurkingAndNavigate(guild.id, null, JoinGuildSources.MOBILE_GUILD_DISCOVERY)
        }
        requireActivity().onBackPressed()
    }

    private fun onHubGuildClicked(guild: com.discord.models.guild.Guild) {
        StoreStream.selectGuild(guild.id)
        requireActivity().onBackPressed()
    }

    private fun onHubClicked() {
        AppScreenRouter.launch(
            requireContext(),
            WidgetHubEmailFlow::class.java,
            HubEmailArgs().toArgsIntent(),
        )
    }

    companion object {
        private const val LOAD_MORE_THRESHOLD = 5

        fun launch(context: Context) =
            AppScreenRouter.launch(context, WidgetGuildDiscovery::class.java)
    }
}
