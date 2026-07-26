package com.discord.widgets.search.results

import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.search.network.state.QueryFetchState
import com.discord.utilities.search.network.state.SearchState
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.entries.SearchEmptyEntry
import com.discord.widgets.chat.list.entries.SearchErrorEntry
import com.discord.widgets.chat.list.entries.SearchIndexingEntry
import com.discord.widgets.chat.list.entries.SearchResultCountEntry
import com.discord.widgets.chat.list.model.ChatListRows
import com.discord.widgets.search.SearchStore
import com.discord.widgets.search.SearchStoreProvider
import rx.Observable

/**
 * Turns the search state into chat rows.
 *
 * Results are rendered by the ordinary chat list, one hit at a time, each preceded by a header
 * naming the guild and channel it came from — hits come from all over a guild, so a plain message
 * list would be unreadable. A finished search is topped by the result count; an unfinished one shows
 * exactly one row: spinner, "still indexing", or the error card.
 */
class WidgetSearchResultsViewModel(
    searchStore: SearchStore = SearchStoreProvider.get(),
) : AppViewModel<WidgetSearchResultsViewModel.ViewState>() {

    /**
     * [WidgetChatListAdapter.Data] as the search surface needs it: no single owning channel, no
     * unread marker, and spoilers locked because a search result is not the message itself.
     */
    data class ViewState(
        override val list: List<ChatListEntry>,
        override val userId: Long,
        override val channelNames: Map<Long, String>,
        override val oldestMessageId: Long,
        override val myRoleIds: Set<Long>,
    ) : WidgetChatListAdapter.Data {
        override val channelId: Long = 0L
        override val guild: Guild? = null
        override val guildId: Long = 0L
        override val newMessagesMarkerMessageId: Long = 0L
        override val isSpoilerClickAllowed: Boolean = false
    }

    init {
        searchStore.storeSearchQuery.state
            .switchMap(::buildRows)
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .subscribe(::updateViewState)
    }

    private fun buildRows(searchState: SearchState): Observable<ViewState> = Observable
        .combineLatest(
            StoreStream.getUsers().observeMe().map { it.id },
            StoreStream.getChannels().observeAllChannels(),
            StoreStream.getGuilds().observeGuilds(),
            StoreStream.getGuilds().observeComputed(),
        ) { meId, channels, guilds, members ->
            val rows = ArrayList<ChatListEntry>()
            val myRoleIds = HashSet<Long>()
            var oldestMessageId = 0L

            val settled = searchState.queryFetchState == QueryFetchState.COMPLETED ||
                searchState.queryFetchState == QueryFetchState.LOADING_MORE
            if (settled) {
                val hits = searchState.hits.orEmpty()
                if (hits.isNotEmpty()) {
                    // Pagination walks backwards from the last hit on screen.
                    oldestMessageId = hits.last().id
                    rows += SearchResultCountEntry(searchState.totalResults)
                }

                hits.forEach { message ->
                    val channel = channels[message.channelId] ?: return@forEach
                    val guildMembers = members[channel.guildId].orEmpty()
                    // The viewer's own roles decide which role mentions highlight; collected across
                    // every guild the results span, because one list can cross guilds.
                    myRoleIds += guildMembers[meId]?.getRoles().orEmpty()
                    rows += ChatListRows.contextMessageRows(
                        ChatListRows.State(
                            channel = channel,
                            guild = guilds[channel.guildId],
                            guildMembers = guildMembers,
                            guildRoles = StoreStream.getGuilds().getRoles()[channel.guildId].orEmpty(),
                            animateEmojis = StoreStream.getUserSettings().getIsAnimatedEmojisEnabled(),
                            autoPlayGifs = StoreStream.getUserSettings().getIsAutoPlayGifsEnabled(),
                            renderEmbeds = StoreStream.getUserSettings().getIsRenderEmbedsEnabled(),
                        ),
                        message,
                    )
                }

                when {
                    searchState.queryFetchState == QueryFetchState.LOADING_MORE -> rows += LoadingEntry()
                    rows.isEmpty() -> rows += SearchEmptyEntry()
                }
            } else {
                when (searchState.queryFetchState) {
                    QueryFetchState.IN_PROGRESS -> rows += LoadingEntry()
                    QueryFetchState.INDEXING -> rows += SearchIndexingEntry()
                    QueryFetchState.FAILED -> rows += SearchErrorEntry()
                    else -> Unit
                }
            }

            ViewState(rows, meId, channelNames(channels), oldestMessageId, myRoleIds)
        }

    private fun channelNames(channels: Map<Long, Channel>): Map<Long, String> =
        channels.mapValues { (_, channel) -> channel.getDisplayName() }
}
