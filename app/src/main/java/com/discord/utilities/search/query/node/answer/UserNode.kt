package com.discord.utilities.search.query.node.answer

import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.validation.SearchData
import java.util.Locale
import com.discord.utilities.user.UserUtils

/**
 * `from:alice#0001` / `mentions:alice#0001`.
 *
 * The same node answers both filters and picks the REST parameter from whichever one preceded it.
 */
data class UserNode(val userName: String, val discriminator: Int) : AnswerNode() {

    override val text: String
        get() = userName + UserUtils.discriminatorSuffix(discriminator)

    override val validFilters: Set<FilterType>
        get() = setOf(FilterType.FROM, FilterType.MENTIONS)

    override fun isValid(searchData: SearchData): Boolean =
        searchData.getUserId(userName, discriminator) != null

    override fun updateQuery(
        queryBuilder: SearchQuery.Builder,
        searchData: SearchData,
        filterType: FilterType?,
    ) {
        val paramKey = when (filterType) {
            FilterType.FROM -> SearchQuery.PARAM_AUTHOR_ID
            FilterType.MENTIONS -> SearchQuery.PARAM_MENTIONS
            else -> return
        }
        queryBuilder.appendParam(paramKey, searchData.getUserId(userName, discriminator).toString())
    }
}
