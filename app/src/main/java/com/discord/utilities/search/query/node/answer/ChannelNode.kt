package com.discord.utilities.search.query.node.answer

import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.validation.SearchData

/** `in:#channel-name`. Resolves the name back to an id against the guild's searchable channels. */
data class ChannelNode(val channelName: String) : AnswerNode() {

    override val text: String get() = "#$channelName"

    override val validFilters: Set<FilterType> get() = setOf(FilterType.IN)

    override fun isValid(searchData: SearchData): Boolean =
        searchData.channelNameIndex.containsKey(channelName)

    override fun updateQuery(
        queryBuilder: SearchQuery.Builder,
        searchData: SearchData,
        filterType: FilterType?,
    ) {
        if (filterType !in validFilters) return
        val channelId = searchData.channelNameIndex[channelName] ?: return
        queryBuilder.appendParam(SearchQuery.PARAM_CHANNEL_ID, channelId.toString())
    }
}
