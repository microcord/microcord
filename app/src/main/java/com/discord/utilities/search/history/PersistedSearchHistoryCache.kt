package com.discord.utilities.search.history

import com.discord.utilities.persister.Persister
import com.discord.utilities.search.SearchTarget
import com.discord.utilities.search.query.node.QueryNode
import rx.Observable
import java.util.LinkedList

/**
 * [SearchHistoryCache] on top of [Persister], so history survives a restart.
 *
 * Both levels are most-recent-first and both are capped: at most ten targets are remembered, and at
 * most five queries within each. Re-running a query moves it (and its target) back to the front
 * rather than duplicating it.
 *
 * The reference class is `MGPreferenceSearchHistoryCache`; the persistence key `SEARCH_HISTORY_V2`
 * is preserved so a device upgraded from the official client keeps its history.
 */
class PersistedSearchHistoryCache : SearchHistoryCache {

    private data class TargetHistory(
        val searchTarget: SearchTarget,
        val recentQueries: LinkedList<List<QueryNode>>,
    )

    private val backingCache =
        Persister<LinkedList<TargetHistory>>(PERSISTENCE_KEY, LinkedList())

    override fun getHistory(searchTarget: SearchTarget): Observable<Collection<List<QueryNode>>> =
        backingCache.getObservable().map { it.find(searchTarget) }

    override fun persistQuery(searchTarget: SearchTarget, query: List<QueryNode>) {
        if (query.isEmpty()) return
        backingCache.set(backingCache.get().putAndCopy(searchTarget, query), persist = true)
    }

    override fun clear(searchTarget: SearchTarget) {
        backingCache.set(backingCache.get().removeAndCopy(searchTarget), persist = true)
    }

    private fun LinkedList<TargetHistory>.find(key: SearchTarget): Collection<List<QueryNode>> =
        firstOrNull { it.searchTarget == key }?.recentQueries ?: emptyList()

    private fun LinkedList<TargetHistory>.putAndCopy(
        searchTarget: SearchTarget,
        query: List<QueryNode>,
    ): LinkedList<TargetHistory> {
        val remaining = LinkedList(this)
        val existing = remaining.firstOrNull { it.searchTarget == searchTarget }
        if (existing != null) remaining.remove(existing)

        val queries = LinkedList(existing?.recentQueries.orEmpty())
        queries.remove(query)
        queries.addFirst(query)

        remaining.addFirst(
            TargetHistory(searchTarget, LinkedList(queries.take(MAX_QUERIES_PER_TARGET))),
        )
        return LinkedList(remaining.take(MAX_SEARCH_TARGETS))
    }

    private fun LinkedList<TargetHistory>.removeAndCopy(
        searchTarget: SearchTarget,
    ): LinkedList<TargetHistory> =
        LinkedList(filterNot { it.searchTarget == searchTarget })

    companion object {
        private const val PERSISTENCE_KEY = "SEARCH_HISTORY_V2"
        private const val MAX_SEARCH_TARGETS = 10
        private const val MAX_QUERIES_PER_TARGET = 5
    }
}
