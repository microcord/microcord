package com.discord.widgets.discovery

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.discovery.DiscoverableGuild
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetGuildDiscoverySearchBinding
import com.discord.restapi.RestAPIInterface
import com.discord.stores.JoinGuildSources
import com.discord.stores.StoreStream
import com.discord.utilities.locale.LocaleManager
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.subjects.PublishSubject
import java.util.concurrent.TimeUnit

class WidgetGuildDiscoverySearch : AppFragment(R.layout.widget_guild_discovery_search) {
    private var _binding: WidgetGuildDiscoverySearchBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val api: RestAPIInterface get() = RestAPI.api
    private val queries = PublishSubject.create<String>()
    private val adapter = GuildDiscoveryAdapter(::onGuildClicked)

    private var categoryId = GuildDiscoveryTab.ALL_CATEGORIES_ID
    private var query = ""
    private var categoryNames: Map<Int, String> = emptyMap()
    private var counts: List<Pair<Int, Int>> = emptyList()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetGuildDiscoverySearchBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.guild_discovery_mobile_search_placeholder)
        categoryId = mostRecentIntent.getIntExtra(ARG_CATEGORY_ID, GuildDiscoveryTab.ALL_CATEGORIES_ID)
        binding.discoverySearchList.adapter = adapter
        binding.discoverySearchInput.bindTextWatcher(this) { queries.onNext(it) }
        showProtip()
        fetchCategoryNames()

        queries
            .debounce(SEARCH_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .distinctUntilChanged()
            .ui(this)
            .appSubscribe(WidgetGuildDiscoverySearch::class.java) { search(it) }
    }

    override fun onDestroyView() {
        binding.discoverySearchList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun locale() = LocaleManager().getPrimaryLocale(context).toLanguageTag()

    private fun fetchCategoryNames() {
        api.getDiscoveryCategories(locale())
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetGuildDiscoverySearch::class.java, errorHandler = {}) { fetched ->
                categoryNames = fetched.associate { it.id to it.name }
                renderCategories()
            }
    }

    private fun search(query: String) {
        this.query = query
        if (query.length < SEARCH_MIN_LENGTH) {
            counts = emptyList()
            renderCategories()
            showProtip()
            return
        }
        binding.discoverySearchLoading.visibility = View.VISIBLE
        api.searchDiscoverableGuilds(
            query = query,
            offset = 0,
            limit = PAGE_SIZE,
            categoryId = categoryId.takeIf { it != GuildDiscoveryTab.ALL_CATEGORIES_ID },
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetGuildDiscoverySearch::class.java,
                errorHandler = { showMessage(R.string.guild_discovery_search_error) },
            ) { response ->
                binding.discoverySearchLoading.visibility = View.GONE
                response.categories?.let { returned ->
                    counts = returned
                        .mapNotNull { entry -> entry.id.toIntOrNull()?.let { it to entry.count } }
                        .sortedByDescending { it.second }
                    renderCategories()
                }
                if (response.guilds.isEmpty()) {
                    showMessage(R.string.guild_discovery_search_empty_header)
                } else {
                    adapter.submitList(response.guilds.map { GuildDiscoveryAdapter.Item.Server(it) })
                }
            }
    }

    /**
     * The buckets the current results actually fall into, biggest first, rather than every category
     * the server offers.
     */
    private fun renderCategories() {
        val row = binding.discoverySearchPills
        row.removeAllViews()
        counts.take(MAX_CATEGORY_FILTERS).forEach { (id, count) ->
            val name = if (id == GuildDiscoveryTab.ALL_CATEGORIES_ID) {
                getString(R.string.guild_discovery_all_category_filter)
            } else {
                categoryNames[id]
            } ?: return@forEach
            addPill(row, getString(R.string.manticord_discovery_category_count, name, count), id)
        }
    }

    private fun addPill(row: LinearLayout, label: String, id: Int) {
        val pill = layoutInflater.inflate(R.layout.guild_discovery_tab, row, false) as TextView
        pill.text = label
        pill.isSelected = id == categoryId
        pill.setOnClickListener {
            if (categoryId == id) return@setOnClickListener
            categoryId = id
            renderCategories()
            search(query)
        }
        row.addView(pill)
    }

    private fun showProtip() = showMessage(R.string.guild_discovery_search_protip)

    private fun showMessage(messageRes: Int) {
        binding.discoverySearchLoading.visibility = View.GONE
        adapter.submitList(listOf(GuildDiscoveryAdapter.Item.Empty(messageRes)))
    }

    private fun onGuildClicked(guild: DiscoverableGuild) {
        if (StoreStream.getGuilds().getGuild(guild.id) != null) {
            StoreStream.selectGuild(guild.id)
        } else {
            StoreStream.getLurking().startLurkingAndNavigate(guild.id, null, JoinGuildSources.MOBILE_GUILD_DISCOVERY)
        }
        requireActivity().onBackPressed()
    }

    companion object {
        private const val ARG_CATEGORY_ID = "ARG_CATEGORY_ID"
        private const val SEARCH_MIN_LENGTH = 2
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val PAGE_SIZE = 24
        private const val MAX_CATEGORY_FILTERS = 7

        fun launch(context: Context, tab: GuildDiscoveryTab) = AppScreenRouter.launch(
            context,
            WidgetGuildDiscoverySearch::class.java,
            Intent().putExtra(ARG_CATEGORY_ID, tab.categoryId),
        )
    }
}
