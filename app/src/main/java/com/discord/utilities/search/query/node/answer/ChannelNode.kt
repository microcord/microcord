package com.discord.utilities.search.query.node.answer

import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.validation.SearchData

/**
 * `in:#channel-name`, or `in:"name with spaces"`. Resolves the name back to the ids of every
 * searchable channel that carries it, as RN does.
 */
data class ChannelNode(val channelName: String) : AnswerNode() {

    override val text: String
        get() = if (channelName.any(Char::isWhitespace)) {
            "#\"" + channelName.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            "#$channelName"
        }

    override val validFilters: Set<FilterType> get() = setOf(FilterType.IN)

    override fun isValid(searchData: SearchData): Boolean = searchData.getChannelIds(channelName).isNotEmpty()

    override fun updateQuery(
        queryBuilder: SearchQuery.Builder,
        searchData: SearchData,
        filterType: FilterType?,
    ) {
        if (filterType !in validFilters) return
        searchData.getChannelIds(channelName).forEach {
            queryBuilder.appendParam(SearchQuery.PARAM_CHANNEL_ID, it.toString())
        }
    }
}
