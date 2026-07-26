package com.discord.utilities.search.query.node.answer

import android.content.Context
import android.text.SpannableStringBuilder
import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.validation.SearchData

/**
 * The value that answers a filter — the user after `from:`, the channel after `in:`, the media kind
 * after `has:`.
 *
 * An answer is only meaningful under the filters in [validFilters]; under anything else the query
 * builder treats it as free text instead.
 */
abstract class AnswerNode : QueryNode() {

    /** The filters this answer can follow. */
    abstract val validFilters: Set<FilterType>

    /** Whether the thing this node names still exists in the searched guild or channel. */
    abstract fun isValid(searchData: SearchData): Boolean

    /** Contributes this answer's REST parameter, when [filterType] is one it answers. */
    abstract fun updateQuery(
        queryBuilder: SearchQuery.Builder,
        searchData: SearchData,
        filterType: FilterType?,
    )

    override fun render(builder: SpannableStringBuilder, renderContext: Context) {
        builder.append(text).append(' ')
    }
}
