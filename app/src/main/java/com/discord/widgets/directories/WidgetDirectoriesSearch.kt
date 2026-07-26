package com.discord.widgets.directories

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.i18n.i18nRender
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.hubs.hubArgs
import com.discord.widgets.hubs.toArgsIntent
import com.discord.widgets.hubs.withHubHook
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Search within one directory channel.
 *
 * Reached from the directory channel's [com.discord.views.directories.ServerDiscoveryHeader] and
 * from the `/discovery` deep link. Everything below the search bar is one of four states, driven by
 * [WidgetDirectoriesSearchViewModel.ViewState.loadState]: the pro-tip before the first search, a
 * spinner-equivalent while one is in flight, the results, or "no matches" / a retry.
 */
class WidgetDirectoriesSearch : AppFragment(R.layout.widget_directories_search) {

    private val args: DirectoriesSearchArgs by hubArgs<DirectoriesSearchArgs>()
    private val viewModel: WidgetDirectoriesSearchViewModel by appViewModels {
        WidgetDirectoriesSearchViewModel(args.channelId)
    }

    private val addServerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.retry() }

    private lateinit var adapter: WidgetDirectoryChannelAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyDescription: LinkifiedTextView
    private lateinit var searchBar: TextInputLayout
    private lateinit var searchBarText: TextInputEditText
    private lateinit var searchButton: FloatingActionButton

    private val clickListener = object : DirectoryChannelItemClickInterface {
        override fun onEntryClicked(guildId: Long, channelId: Long) {
            val context = context ?: return
            viewModel.joinGuild(context, guildId, channelId)
        }

        override fun onGoToGuildClicked(guildId: Long) {
            StoreStream.getGuildSelected().set(guildId)
            activity?.onBackPressed()
        }

        override fun onOverflowClicked(
            directoryEntry: DirectoryEntryGuild,
            channelId: Long,
            isServerOwner: Boolean,
        ) {
            DirectoryServerOptions.show(
                this@WidgetDirectoriesSearch,
                directoryEntry,
                viewModel.getHubName(),
                isServerOwner,
            ) { viewModel.removeGuild(directoryEntry.guild.id, channelId) }
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        recycler = view.findViewById(R.id.recycler_view)
        emptyState = view.findViewById(R.id.empty_state)
        emptyTitle = view.findViewById(R.id.empty_title)
        emptyDescription = view.findViewById(R.id.empty_description)
        searchBar = view.findViewById(R.id.search_bar)
        searchBarText = view.findViewById(R.id.search_bar_text)
        searchButton = view.findViewById(R.id.search)

        adapter = MGRecyclerAdapter.configure(WidgetDirectoryChannelAdapter(recycler, clickListener))

        searchBar.setStartIconOnClickListener {
            activity?.onBackPressed()
            hideKeyboard(view)
        }
        searchButton.setOnClickListener { searchForDirectories() }
        searchBarText.setOnEditorActionListener { _, _, _ ->
            searchForDirectories()
            true
        }
        searchBarText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = Unit
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                searchButton.visibility =
                    if (viewModel.shouldNotSearch(text ?: "")) View.GONE else View.VISIBLE
            }
        })
        showKeyboard(searchBarText)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(WidgetDirectoriesSearch::class.java) { configureUi(it) }
    }

    private fun searchForDirectories() {
        viewModel.searchForDirectories(args.channelId, searchBarText.text.toString())
        hideKeyboard(view)
    }

    private fun configureUi(state: WidgetDirectoriesSearchViewModel.ViewState) {
        adapter.setData(state.directoryEntryData.map { DirectoryChannelItem.DirectoryItem(it) })

        val hasResults = state.loadState == DirectoryLoadState.Content
        recycler.visibility = if (hasResults) View.VISIBLE else View.GONE
        emptyState.visibility = if (hasResults) View.GONE else View.VISIBLE
        searchButton.visibility =
            if (viewModel.shouldNotSearch(searchBarText.text.toString())) View.GONE else View.VISIBLE
        if (hasResults) return

        val hasQuery = !searchBarText.text.isNullOrBlank()
        // The "no matches" title only makes sense once a query has actually been typed; before that
        // the empty state is a pro-tip about how to search.
        emptyTitle.visibility =
            if (hasQuery && state.loadState == DirectoryLoadState.Empty) View.VISIBLE else View.GONE

        emptyDescription.text = when {
            state.loadState == DirectoryLoadState.Loading -> getString(R.string.loading)
            state.loadState == DirectoryLoadState.Error ->
                getString(R.string.network_error_unknown) + " " + getString(R.string.try_again)
            !hasQuery -> requireContext().i18nRender(R.string.guild_discovery_search_protip)
            !state.hasAddGuildPermissions -> getString(R.string.directory_search_no_results_no_add)
            else -> requireContext()
                .i18nRender(R.string.directory_search_no_results_subtitle)
                .withHubHook(ADD_SERVER_HOOK) {
                    DirectoryNavigation.addServerToHub?.invoke(requireContext(), addServerLauncher)
                }
        }
        emptyState.setOnClickListener(
            if (state.loadState == DirectoryLoadState.Error) {
                View.OnClickListener { viewModel.retry() }
            } else {
                null
            },
        )
    }

    companion object {
        /** The action marker inside `directory_search_no_results_subtitle`. */
        private const val ADD_SERVER_HOOK = "addServerHook"

        /**
         * The Discord-owned "HQ" directory channel, which is what `/discovery` opens.
         *
         * `ChannelUtils` keeps the same constant private; promote it there and delete this copy.
         */
        private const val HQ_DIRECTORY_CHANNEL_ID = 883060064561299456L

        /** Intent that opens directory search for [channelId]. */
        @JvmStatic
        fun argsIntent(channelId: Long): Intent = DirectoriesSearchArgs(channelId).toArgsIntent()

        /** Intent that opens the `/discovery` deep link's destination. */
        @JvmStatic
        fun discoveryIntent(): Intent = argsIntent(HQ_DIRECTORY_CHANNEL_ID)
    }
}
