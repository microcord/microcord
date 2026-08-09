package com.discord.utilities.search.query.node.answer

import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.validation.SearchData

/** `authorType:bot`, or `authorType:-bot` to exclude. */
data class AuthorTypeNode(
    val authorTypeAnswerOption: AuthorTypeAnswerOption,
    val negated: Boolean,
    override val text: CharSequence,
) : AnswerNode() {

    constructor(match: String, searchStringProvider: SearchStringProvider) : this(
        AuthorTypeAnswerOption.getOptionFromString(match, searchStringProvider),
        match.startsWith(HasAnswerOption.NEGATION_PREFIX),
        match,
    )

    override val validFilters: Set<FilterType> get() = setOf(FilterType.AUTHOR_TYPE)

    override fun isValid(searchData: SearchData): Boolean = true

    override fun updateQuery(
        queryBuilder: SearchQuery.Builder,
        searchData: SearchData,
        filterType: FilterType?,
    ) {
        if (filterType !in validFilters) return
        val prefix = if (negated) HasAnswerOption.NEGATION_PREFIX else ""
        queryBuilder.appendParam(SearchQuery.PARAM_AUTHOR_TYPE, prefix + authorTypeAnswerOption.restParamValue)
    }
}
