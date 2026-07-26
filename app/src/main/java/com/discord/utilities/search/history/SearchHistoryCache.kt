package com.discord.utilities.search.history

import com.discord.utilities.search.SearchTarget
import com.discord.utilities.search.query.node.QueryNode
import rx.Observable

/** Recent queries, remembered per guild or private channel. */
interface SearchHistoryCache {
    fun getHistory(searchTarget: SearchTarget): Observable<Collection<List<QueryNode>>>
    fun persistQuery(searchTarget: SearchTarget, query: List<QueryNode>)
    fun clear(searchTarget: SearchTarget)
}
