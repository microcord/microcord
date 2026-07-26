package com.discord.widgets.directories

import android.content.Intent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.isHQDirectoryChannel
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.models.hubs.DirectoryEntryCategory
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.hubs.hubArgs

/**
 * One tab of the directory channel: the list of servers in a single category.
 *
 * The "Home" tab is special — instead of one flat list it shows a "Newest" section (recently listed,
 * featurable servers) followed by everything else under "All Servers". The HQ directory has no
 * sections at all.
 *
 * Loading and error states are *not* rendered here: the host [WidgetDirectoryChannel] hides the
 * whole pager until it has entries, so a category page only ever exists over loaded data.
 */
class WidgetDirectoryCategory : AppFragment(R.layout.widget_directory_category) {

    private val args: DirectoryCategoryArgs by hubArgs<DirectoryCategoryArgs>()
    private val viewModel: WidgetDirectoriesViewModel by appActivityViewModels {
        WidgetDirectoriesViewModel()
    }

    private lateinit var adapter: WidgetDirectoryChannelAdapter

    /**
     * The add-server flow lives in `widgets/hubs`, is started for a result, and refreshes the page
     * when it returns. Registered eagerly because `registerForActivityResult` must run before the
     * fragment is STARTED.
     */
    private val addServerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.retry() }

    private val clickListener = object : DirectoryChannelItemClickInterface {
        override fun onAddServerClicked() {
            val context = context ?: return
            DirectoryNavigation.addServerToHub?.invoke(context, addServerLauncher)
        }

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
                this@WidgetDirectoryCategory,
                directoryEntry,
                viewModel.getHubName(),
                isServerOwner,
            ) { viewModel.removeGuild(directoryEntry.guild.id, channelId) }
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        adapter = MGRecyclerAdapter.configure(WidgetDirectoryChannelAdapter(recycler, clickListener))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(WidgetDirectoryCategory::class.java) { configureUi(it) }
    }

    private fun configureUi(state: WidgetDirectoriesViewModel.ViewState) {
        adapter.setData(
            if (args.directoryCategory == DirectoryEntryCategory.Home) {
                homeRows(state)
            } else {
                categoryRows(state)
            },
        )
    }

    /** A single category's entries, plus the add-server row when the viewer may add one. */
    private fun categoryRows(
        state: WidgetDirectoriesViewModel.ViewState,
    ): List<DirectoryChannelItem> {
        val rows = state.directoryEntryData[args.directoryCategory.key].orEmpty().toRows()
        if (state.hasAddGuildPermissions) rows += DirectoryChannelItem.AddServer
        return rows
    }

    /**
     * The Home tab. The HQ directory is a flat list; a student hub is split into "Newest" and
     * "All Servers", with the newest servers appearing in the first section only.
     */
    private fun homeRows(state: WidgetDirectoriesViewModel.ViewState): List<DirectoryChannelItem> {
        val channel = state.channel ?: return emptyList()
        if (channel.isHQDirectoryChannel()) {
            val rows = state.allDirectoryEntryData.toRows()
            if (state.hasAddGuildPermissions) rows += DirectoryChannelItem.AddServer
            return rows
        }

        val rows = mutableListOf<DirectoryChannelItem>()
        val newest = state.allDirectoryEntryData.sortByAdded()
        if (newest.isNotEmpty()) {
            rows += DirectoryChannelItem.SectionHeader(R.string.search_newest)
            rows += newest.toRows()
        }
        val newestGuildIds = newest.mapTo(LinkedHashSet<Long>()) { it.entry.guild.id }
        val remaining = state.allDirectoryEntryData.filterNot { it.entry.guild.id in newestGuildIds }
        if (remaining.isNotEmpty()) {
            rows += DirectoryChannelItem.SectionHeader(R.string.all_servers)
            rows += remaining.toRows()
        }
        return rows
    }

    private fun List<DirectoryEntryData>.toRows(): MutableList<DirectoryChannelItem> =
        mapTo(mutableListOf()) { DirectoryChannelItem.DirectoryItem(it) }
}
