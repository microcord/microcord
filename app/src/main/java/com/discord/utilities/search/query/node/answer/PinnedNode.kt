package com.discord.utilities.search.query.node.answer

import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.validation.SearchData

/**
 * `pinned:true` / `pinned:false`.
 *
 * The reference does not offer this in the mobile filter picker, but its tokenizer still parses it,
 * so a query typed by hand or restored from history keeps working.
 */
data class PinnedNode(
    val pinned: Boolean,
    override val text: CharSequence,
) : AnswerNode() {

    override val validFilters: Set<FilterType> get() = setOf(FilterType.PINNED)

    override fun isValid(searchData: SearchData): Boolean = true

    override fun updateQuery(
        queryBuilder: SearchQuery.Builder,
        searchData: SearchData,
        filterType: FilterType?,
    ) {
        if (filterType !in validFilters) return
        queryBuilder.appendParam(SearchQuery.PARAM_PINNED, pinned.toString())
    }

    companion object {
        fun parse(value: String): Boolean? = when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}
