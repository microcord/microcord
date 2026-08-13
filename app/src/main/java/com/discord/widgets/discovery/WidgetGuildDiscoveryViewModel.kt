package com.discord.widgets.discovery

import com.discord.api.discovery.DiscoverableGuild
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetGuildDiscoveryViewModel(
    private val api: RestAPIInterface = RestAPI.api,
    joinedHubs: Observable<List<Guild>> =
        StoreStream.getGuilds().observeGuilds().map { guilds -> guilds.values.filter { it.isHub() } },
) : AppViewModel<WidgetGuildDiscoveryViewModel.ViewState>(ViewState(GuildDiscoveryTab.FEATURED)) {

    data class ViewState(
        val tab: GuildDiscoveryTab,
        val guilds: List<DiscoverableGuild> = emptyList(),
        val joinedHubs: List<Guild> = emptyList(),
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val hasFailed: Boolean = false,
    )

    private class Page(
        val guilds: MutableList<DiscoverableGuild> = mutableListOf(),
        var total: Int = 0,
        var exhausted: Boolean = false,
    )

    private val pages = HashMap<Int, Page>()
    private var inFlight: Int? = null

    init {
        joinedHubs.ui(this).appSubscribe(WidgetGuildDiscoveryViewModel::class.java) { hubs ->
            withViewState { updateViewState(it.copy(joinedHubs = hubs)) }
        }
        fetch(GuildDiscoveryTab.FEATURED)
    }

    fun selectTab(tab: GuildDiscoveryTab) {
        if (requireViewState().tab == tab) return
        val page = pages[tab.categoryId]
        updateViewState(
            requireViewState().copy(
                tab = tab,
                guilds = page?.guilds.orEmpty(),
                isLoading = page == null && tab != GuildDiscoveryTab.HUBS,
                isLoadingMore = false,
                hasFailed = false,
            ),
        )
        if (page == null) fetch(tab)
    }

    fun loadMore() {
        val state = requireViewState()
        val page = pages[state.tab.categoryId] ?: return
        if (state.isLoading || state.isLoadingMore || page.exhausted) return
        updateViewState(state.copy(isLoadingMore = true))
        fetch(state.tab)
    }

    fun retry() = fetch(requireViewState().tab)

    private fun fetch(tab: GuildDiscoveryTab) {
        if (tab == GuildDiscoveryTab.HUBS || inFlight == tab.categoryId) return
        inFlight = tab.categoryId
        val offset = pages[tab.categoryId]?.guilds?.size ?: 0
        api.getDiscoverableGuilds(
            offset = offset,
            limit = PAGE_SIZE,
            categoryId = tab.categoryId.takeIf { it != GuildDiscoveryTab.ALL_CATEGORIES_ID },
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetGuildDiscoveryViewModel::class.java,
                errorHandler = { onFailed(tab) },
            ) { response ->
                inFlight = null
                val page = pages.getOrPut(tab.categoryId) { Page() }
                page.guilds += response.guilds
                page.total = response.total
                page.exhausted = response.guilds.isEmpty() || page.guilds.size >= response.total
                publish(tab, page)
            }
    }

    private fun onFailed(tab: GuildDiscoveryTab) {
        inFlight = null
        val state = requireViewState()
        if (state.tab != tab) return
        // A failed later page keeps what is already on screen; only a failed first page is fatal.
        val page = pages[tab.categoryId]
        if (page == null) {
            updateViewState(state.copy(isLoading = false, isLoadingMore = false, hasFailed = true))
        } else {
            page.exhausted = true
            updateViewState(state.copy(isLoading = false, isLoadingMore = false))
        }
    }

    private fun publish(tab: GuildDiscoveryTab, page: Page) {
        val state = requireViewState()
        if (state.tab != tab) return
        updateViewState(
            state.copy(
                guilds = page.guilds.toList(),
                isLoading = false,
                isLoadingMore = false,
                hasFailed = false,
            ),
        )
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
