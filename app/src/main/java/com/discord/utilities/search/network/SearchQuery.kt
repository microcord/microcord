package com.discord.utilities.search.network

import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.answer.AnswerNode
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.validation.SearchData

/**
 * A parsed query flattened into the REST parameters of `messages/search`.
 *
 * Every key can repeat — two `from:` filters mean two `author_id` values — which is why [params] is
 * a map of lists rather than a map of strings.
 */
class SearchQuery(val params: Map<String, List<String>>, val includeNsfw: Boolean) {

    /** A query with no parameters at all would match everything, so it is never sent. */
    val isValid: Boolean get() = params.isNotEmpty()

    /**
     * Walks a query AST once, in order, remembering the filter most recently seen.
     *
     * An answer contributes its parameter only when it follows a filter it actually answers;
     * otherwise it degrades to free text, so typing `has:` and then a username searches for the
     * username rather than silently dropping it.
     *
     * A filter binds to exactly one node: the filter is cleared again as soon as any answer or
     * content node has been consumed, so `from: alice#0001 bob#0002` searches for messages by alice
     * containing the text `bob#0002`, not for messages by either user. Confirmed at bytecode level —
     * the `FilterNode` branch of `SearchQuery$Builder.buildFrom` jumps to the loop head while both
     * consuming branches jump one instruction earlier, to the `filterType = null` store.
     */
    class Builder {
        private val contentBuilder = StringBuilder()
        private val paramsMap = LinkedHashMap<String, MutableList<String>>()
        private var includeNsfw: Boolean = false

        fun setIncludeNsfw(includeNsfw: Boolean): Builder = apply { this.includeNsfw = includeNsfw }

        fun appendParam(paramKey: String, param: String) {
            paramsMap.getOrPut(paramKey) { ArrayList() }.add(param)
        }

        private fun appendContent(content: CharSequence) {
            contentBuilder.append(content).append(' ')
        }

        fun buildFrom(queryNodes: List<QueryNode>, searchData: SearchData): SearchQuery {
            var currentFilter: FilterType? = null
            queryNodes.forEach { node ->
                when (node) {
                    is FilterNode -> currentFilter = node.filterType
                    is AnswerNode -> {
                        if (currentFilter in node.validFilters) {
                            node.updateQuery(this, searchData, currentFilter)
                        } else {
                            appendContent(node.text)
                        }
                        currentFilter = null
                    }
                    is ContentNode -> {
                        appendContent(node.text)
                        currentFilter = null
                    }
                    // Any other node kind leaves the pending filter in place.
                    else -> Unit
                }
            }
            val content = contentBuilder.toString().trim()
            if (content.isNotEmpty()) paramsMap[PARAM_CONTENT] = mutableListOf(content)
            return SearchQuery(paramsMap, includeNsfw)
        }
    }

    companion object {
        const val PARAM_AUTHOR_ID = "author_id"
        const val PARAM_MENTIONS = "mentions"
        const val PARAM_CHANNEL_ID = "channel_id"
        const val PARAM_HAS = "has"
        const val PARAM_CONTENT = "content"
    }
}
