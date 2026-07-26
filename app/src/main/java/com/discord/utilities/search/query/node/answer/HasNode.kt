package com.discord.utilities.search.query.node.answer

import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.validation.SearchData

/**
 * `has:image` and friends.
 *
 * [text] keeps the localised label the user typed so the input round-trips, while
 * [HasAnswerOption.restParamValue] is what goes on the wire.
 */
data class HasNode(
    val hasAnswerOption: HasAnswerOption,
    override val text: CharSequence,
) : AnswerNode() {

    constructor(match: String, searchStringProvider: SearchStringProvider) :
        this(HasAnswerOption.getOptionFromString(match, searchStringProvider), match)

    override val validFilters: Set<FilterType> get() = setOf(FilterType.HAS)

    /** Always valid: a media kind cannot go stale the way a user or channel can. */
    override fun isValid(searchData: SearchData): Boolean = true

    override fun updateQuery(
        queryBuilder: SearchQuery.Builder,
        searchData: SearchData,
        filterType: FilterType?,
    ) {
        if (filterType !in validFilters) return
        queryBuilder.appendParam(SearchQuery.PARAM_HAS, hasAnswerOption.restParamValue)
    }
}
