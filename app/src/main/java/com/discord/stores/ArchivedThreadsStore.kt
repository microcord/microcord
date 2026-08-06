package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.thread.ThreadListing
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable
import rx.Subscription

/**
 * Paged archived-thread listings for the thread browser and the forum browser.
 *
 * One listing exists per `(channelId, ThreadListingType)` pair and is append-only: [fetchListing]
 * without `reload` continues from the last page, and the cursor is derived from the tail of the
 * pages already held rather than tracked separately, so the cursor can never disagree with the list.
 *
 * Fetched threads are pushed back into the channel store through [handleThreadCreateOrUpdate] so the
 * rest of the app resolves them like any other channel; the listing itself only records order,
 * paging and load state.
 */
class ArchivedThreadsStore(
    private val dispatcher: Dispatcher,
    private val storeForumPostMessages: StoreForumPostMessages,
    private val observationDeck: ObservationDeck,
    private val restApi: () -> RestAPIInterface?,
    private val handleThreadCreateOrUpdate: (Channel) -> Unit,
    private val forumApi: () -> RestAPIInterface? = restApi,
) : StoreV2() {

    sealed class ThreadListingState {
        /** Nothing has been requested for this key yet. */
        object Uninitialized : ThreadListingState()

        /**
         * @param hasMore whether the server reported another page after this one.
         * @param isLoadingMore whether a request is in flight; true for the first page too, which is
         *   how the browser distinguishes an empty listing from one that has not loaded.
         */
        data class Listing(
            val threads: List<Channel>,
            val hasMore: Boolean,
            val isLoadingMore: Boolean,
        ) : ThreadListingState()

        object Error : ThreadListingState()
    }

    /**
     * The three archived-thread endpoints, each with its own cursor convention.
     *
     * The reference reads `RestAPI.Companion.api` inside `fetchNext`; this project has no REST
     * singleton, so the interface is passed in by the store.
     */
    enum class ThreadListingType {
        MY_ARCHIVED_PRIVATE_THREADS {
            /** Cursor is the last thread's id, not its archive timestamp. */
            override fun fetchNext(
                api: RestAPIInterface,
                channelId: Long,
                threads: List<Channel>,
            ): Observable<ThreadListing> =
                api.getMyPrivateArchivedThreads(channelId, threads.lastOrNull()?.id)
        },
        ALL_ARCHIVED_PUBLIC_THREADS {
            override fun fetchNext(
                api: RestAPIInterface,
                channelId: Long,
                threads: List<Channel>,
            ): Observable<ThreadListing> = try {
                api.getAllPublicArchivedThreads(channelId, getLastArchiveTimestamp(threads))
            } catch (failure: IllegalStateException) {
                Observable.error(failure)
            }
        },
        ALL_ARCHIVED_PRIVATE_THREADS {
            override fun fetchNext(
                api: RestAPIInterface,
                channelId: Long,
                threads: List<Channel>,
            ): Observable<ThreadListing> = try {
                api.getAllPrivateArchivedThreads(channelId, getLastArchiveTimestamp(threads))
            } catch (failure: IllegalStateException) {
                Observable.error(failure)
            }
        },
        GUILD_FORUM_THREADS {
            override fun fetchNext(
                api: RestAPIInterface,
                channelId: Long,
                threads: List<Channel>,
            ): Observable<ThreadListing> = api.searchForumThreads(
                channelId = channelId,
                archived = true,
                sortBy = "last_message_time",
                sortOrder = "desc",
                limit = FORUM_PAGE_SIZE,
                offset = threads.size,
            )
        };

        abstract fun fetchNext(
            api: RestAPIInterface,
            channelId: Long,
            threads: List<Channel>,
        ): Observable<ThreadListing>

        companion object {
            private const val FORUM_PAGE_SIZE = 25
            /**
             * The paging cursor: the archive timestamp of the oldest thread held so far, or `null`
             * for the first page.
             *
             * Throws rather than paging from a wrong position when the tail thread arrived without
             * its metadata — silently restarting from the top would duplicate rows forever.
             */
            internal fun getLastArchiveTimestamp(threads: List<Channel>): String? {
                if (threads.isEmpty()) return null
                return checkNotNull(threads.last().threadMetadata?.archiveTimestamp) {
                    "Thread missing threadMetadata"
                }
            }
        }
    }

    private var listings = HashMap<Pair<Long, ThreadListingType>, ThreadListingState>()
    private var listingsSnapshot: Map<Pair<Long, ThreadListingType>, ThreadListingState> = emptyMap()
    private var fetchSubscriptions = HashMap<Pair<Long, ThreadListingType>, Subscription>()
    private val forumSearches = HashMap<Long, ThreadListingState>()
    private var forumSearchesSnapshot: Map<Long, ThreadListingState> = emptyMap()
    private val searchSubscriptions = HashMap<Long, Subscription>()

    // -- Commands -------------------------------------------------------------------------------

    @JvmOverloads
    fun fetchListing(
        channelId: Long,
        threadListingType: ThreadListingType,
        reload: Boolean = false,
        apiOverride: RestAPIInterface? = null,
        onTerminated: (() -> Unit)? = null,
    ) = dispatcher.schedule {
        val key = channelId to threadListingType
        val existing = listings[key] as? ThreadListingState.Listing
        val currentThreads = if (existing != null && !reload) existing.threads else emptyList()
        listings[key] = existing?.copy(isLoadingMore = true)
            ?: ThreadListingState.Listing(currentThreads, hasMore = true, isLoadingMore = true)
        markChanged()

        fetchSubscriptions[key]?.unsubscribe()
        val api = apiOverride ?: restApi()
        if (api == null) {
            listings[key] = ThreadListingState.Error
            markChanged()
            onTerminated?.invoke()
            return@schedule
        }
        threadListingType.fetchNext(api, channelId, currentThreads)
            .restSubscribeOn()
            .appSubscribe(
                errorClass = javaClass,
                subscriptionHandler = { subscription -> fetchSubscriptions[key] = subscription },
                errorHandler = {
                    dispatcher.schedule {
                        listings[key] = ThreadListingState.Error
                        markChanged()
                    }
                },
                onTerminated = { onTerminated?.invoke() },
            ) { result ->
                dispatcher.schedule {
                    result.threads.forEach(handleThreadCreateOrUpdate)
                    listings[key] = ThreadListingState.Listing(
                        threads = currentThreads + result.threads,
                        hasMore = result.hasMore,
                        isLoadingMore = false,
                    )
                    result.firstMessages?.let(storeForumPostMessages::bulkCreateFirstMessage)
                    markChanged()
                }
            }
    }

    @JvmOverloads
    fun fetchGuildForumThreadListing(
        channelId: Long,
        reload: Boolean = false,
        onTerminated: (() -> Unit)? = null,
    ) = fetchListing(
        channelId,
        ThreadListingType.GUILD_FORUM_THREADS,
        reload,
        forumApi(),
        onTerminated,
    )

    // -- Reads ----------------------------------------------------------------------------------

    /** Always reloads from the first page, so a re-entered browser never resumes mid-list. */
    fun loadAndObserveThreadListing(
        channelId: Long,
        threadListingType: ThreadListingType,
    ): Observable<ThreadListingState> {
        fetchListing(channelId, threadListingType, reload = true)
        return observeListing(channelId, threadListingType)
    }

    /** Unlike [loadAndObserveThreadListing] this continues an existing listing rather than reloading. */
    fun loadAndObserveGuildForumThreadListing(channelId: Long): Observable<ThreadListingState> {
        fetchGuildForumThreadListing(channelId)
        return observeGuildForumThreadListing(channelId)
    }

    fun observeGuildForumThreadListing(channelId: Long): Observable<ThreadListingState> =
        observeListing(channelId, ThreadListingType.GUILD_FORUM_THREADS)

    /**
     * Forum search, which is a listing of its own rather than a filter over the loaded one: the
     * server matches archived posts the browser has never paged in.
     */
    fun searchForumThreads(channelId: Long, query: String, tagSetting: String?) = dispatcher.schedule {
        val api = restApi()
        if (api == null) {
            forumSearches[channelId] = ThreadListingState.Error
            markChanged()
            return@schedule
        }
        forumSearches[channelId] = ThreadListingState.Listing(emptyList(), hasMore = false, isLoadingMore = true)
        markChanged()
        searchSubscriptions[channelId]?.unsubscribe()
        api.searchForumThreads(
            channelId = channelId,
            name = query,
            tagSetting = tagSetting,
            sortBy = "last_message_time",
            sortOrder = "desc",
            limit = FORUM_SEARCH_PAGE_SIZE,
        )
            .restSubscribeOn()
            .appSubscribe(
                errorClass = javaClass,
                subscriptionHandler = { subscription -> searchSubscriptions[channelId] = subscription },
                errorHandler = {
                    dispatcher.schedule {
                        forumSearches[channelId] = ThreadListingState.Error
                        markChanged()
                    }
                },
            ) { result ->
                dispatcher.schedule {
                    result.threads.forEach(handleThreadCreateOrUpdate)
                    forumSearches[channelId] = ThreadListingState.Listing(
                        threads = result.threads,
                        hasMore = false,
                        isLoadingMore = false,
                    )
                    result.firstMessages?.let(storeForumPostMessages::bulkCreateFirstMessage)
                    markChanged()
                }
            }
    }

    fun clearForumSearch(channelId: Long) = dispatcher.schedule {
        searchSubscriptions.remove(channelId)?.unsubscribe()
        if (forumSearches.remove(channelId) != null) markChanged()
    }

    fun observeForumSearch(channelId: Long): Observable<ThreadListingState?> =
        observationDeck.connectRx(this) { forumSearchesSnapshot[channelId] }.distinctUntilChanged()

    private fun observeListing(
        channelId: Long,
        threadListingType: ThreadListingType,
    ): Observable<ThreadListingState> = observationDeck.connectRx(this) {
        listingsSnapshot[channelId to threadListingType] ?: ThreadListingState.Uninitialized
    }.distinctUntilChanged()

    // -- Lifecycle ------------------------------------------------------------------------------

    /** A reconnect invalidates every cursor, so listings are dropped rather than resumed. */
    @StoreThread
    fun handleConnectionOpen() {
        listings = HashMap()
        fetchSubscriptions.values.forEach(Subscription::unsubscribe)
        fetchSubscriptions = HashMap()
        markChanged()
    }

    /**
     * Cancel in-flight paging and drop every listing on logout.
     *
     * The reference has no `reset()`; this project wipes user state at logout, so the hook exists to
     * match the surrounding store graph.
     */
    internal fun reset() = handleConnectionOpen()

    @StoreThread
    override fun snapshotData() {
        listingsSnapshot = HashMap(listings)
        forumSearchesSnapshot = HashMap(forumSearches)
    }

    private companion object {
        const val FORUM_SEARCH_PAGE_SIZE = 25
    }
}
