package com.discord.stores

import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationDirectoryCategory
import com.discord.api.applicationdirectory.ApplicationDirectoryCollection
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResponse
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResult
import com.discord.restapi.ApplicationDirectoryAPI
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import java.util.Locale
import rx.Observable

class StoreApplicationDirectory(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val api: () -> ApplicationDirectoryAPI? = { RestAPI.applicationDirectoryApiOrNull },
    private val locale: () -> String = { Locale.getDefault().toLanguageTag() },
) : StoreV2() {
    data class SearchState(
        val query: String = "",
        val categoryId: Int? = null,
        val source: Int = ApplicationDirectoryAPI.SOURCE_APPLICATION_DIRECTORY,
        val results: List<ApplicationDirectorySearchResult> = emptyList(),
        val applications: List<ApplicationDirectoryApplication> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 1,
        val totalCount: Int = 0,
        val countsByCategory: Map<Int, Int> = emptyMap(),
        val loadId: String? = null,
        val loading: Boolean = false,
        val initialized: Boolean = false,
        val error: Boolean = false,
    ) {
        val canLoadMore: Boolean get() = !loading && page < totalPages && page < ApplicationDirectoryAPI.MAX_PAGE
        val empty: Boolean get() = initialized && !loading && !error && applications.isEmpty()
    }

    sealed interface DetailState {
        data object Loading : DetailState
        data object Error : DetailState
        data class Loaded(val application: ApplicationDirectoryApplication) : DetailState
    }

    data class State(
        val collections: List<ApplicationDirectoryCollection> = emptyList(),
        val categories: List<ApplicationDirectoryCategory> = emptyList(),
        val categoriesLoading: Boolean = false,
        val categoriesError: Boolean = false,
        val search: SearchState = SearchState(),
        val details: Map<Long, DetailState> = emptyMap(),
    )

    private var collections = emptyList<ApplicationDirectoryCollection>()
    private var collectionsLoading = false
    private var categories = emptyList<ApplicationDirectoryCategory>()
    private var categoriesLoading = false
    private var categoriesError = false
    private var search = SearchState()
    private val details = mutableMapOf<Long, DetailState>()
    private val detailRequests = mutableMapOf<Long, Long>()
    private var activeSearchRequest = 0L
    private var nextRequest = 0L
    private var snapshot = State()

    fun getState(): State = snapshot
    fun observeState(): Observable<State> = observationDeck.connectRx(this) { getState() }.distinctUntilChanged()

    /** The app launcher's curated shelves; cached by the server for ten minutes. */
    fun loadCollections(surface: Int, force: Boolean = false) = dispatcher.schedule {
        if (collectionsLoading || (!force && collections.isNotEmpty())) return@schedule
        val service = api() ?: return@schedule
        collectionsLoading = true
        service.collections(
            surface = surface,
            activeState = ApplicationDirectoryCollection.ACTIVE_STATE_ACTIVE,
            platform = ApplicationDirectoryCollection.PLATFORM_ANDROID,
            locale = locale(),
        ).restSubscribeOn(false).subscribe(
            { result ->
                dispatcher.schedule {
                    collectionsLoading = false
                    collections = result.sortedBy(ApplicationDirectoryCollection::position)
                    markChanged()
                }
            },
            { dispatcher.schedule { collectionsLoading = false; markChanged() } },
        )
    }

    fun loadCategories(force: Boolean = false) = dispatcher.schedule {
        if (categoriesLoading || (!force && categories.isNotEmpty())) return@schedule
        val service = api()
        if (service == null) {
            categoriesError = true
            markChanged()
            return@schedule
        }
        categoriesLoading = true
        categoriesError = false
        markChanged()
        service.categories(locale()).restSubscribeOn(false).subscribe(
            { result -> dispatcher.schedule { handleCategories(result) } },
            { dispatcher.schedule { handleCategoriesError() } },
        )
    }

    fun search(
        query: String,
        categoryId: Int? = null,
        page: Int = 1,
        source: Int = ApplicationDirectoryAPI.SOURCE_APPLICATION_DIRECTORY,
    ) = dispatcher.schedule {
        val cleanQuery = query.trim().take(MAX_QUERY_LENGTH)
        val cleanCategory = categoryId?.takeUnless { it == FEATURED_CATEGORY_ID }
        val requestedPage = page.coerceIn(1, ApplicationDirectoryAPI.MAX_PAGE)
        if (requestedPage > 1 &&
            (search.query != cleanQuery || search.categoryId != cleanCategory || search.source != source || !search.canLoadMore)
        ) {
            return@schedule
        }
        val requestId = ++nextRequest
        activeSearchRequest = requestId
        search = if (requestedPage == 1) {
            SearchState(query = cleanQuery, categoryId = cleanCategory, source = source, loading = true)
        } else {
            search.copy(loading = true, error = false)
        }
        markChanged()
        val service = api()
        if (service == null) {
            handleSearchError(requestId)
            return@schedule
        }
        service.search(
            cleanQuery.ifBlank { null },
            page = requestedPage,
            pageSize = ApplicationDirectoryAPI.PAGE_SIZE,
            categoryId = cleanCategory,
            locale = locale(),
            source = source,
        ).restSubscribeOn(false).subscribe(
            { response -> dispatcher.schedule { handleSearchSuccess(requestId, requestedPage, response) } },
            { dispatcher.schedule { handleSearchError(requestId) } },
        )
    }

    fun loadNextPage() {
        val current = snapshot.search
        if (current.canLoadMore) search(current.query, current.categoryId, current.page + 1, current.source)
    }

    fun loadApplication(applicationId: Long, force: Boolean = false) = dispatcher.schedule {
        if (!force && details[applicationId] is DetailState.Loaded) return@schedule
        val requestId = ++nextRequest
        detailRequests[applicationId] = requestId
        details[applicationId] = DetailState.Loading
        markChanged()
        val service = api()
        if (service == null) {
            handleDetailError(applicationId, requestId)
            return@schedule
        }
        service.application(applicationId, locale()).restSubscribeOn(false).subscribe(
            { application -> dispatcher.schedule { handleDetail(applicationId, requestId, application) } },
            { dispatcher.schedule { handleDetailError(applicationId, requestId) } },
        )
    }

    @StoreThread
    internal fun handleCategories(value: List<ApplicationDirectoryCategory>) {
        categories = value
        categoriesLoading = false
        categoriesError = false
        markChanged()
    }

    @StoreThread
    internal fun handleCategoriesError() {
        categoriesLoading = false
        categoriesError = true
        markChanged()
    }

    @StoreThread
    internal fun handleSearchSuccess(requestId: Long, page: Int, response: ApplicationDirectorySearchResponse) {
        if (requestId != activeSearchRequest) return
        val mergedResults = if (page == 1) {
            response.results
        } else {
            (search.results + response.results).distinctBy { it.data.id }
        }
        val merged = mergedResults.map { it.data }
        search = search.copy(
            results = mergedResults,
            applications = merged,
            page = page,
            totalPages = response.numPages.coerceAtLeast(page),
            totalCount = response.resultCount,
            countsByCategory = response.countsByCategory,
            loadId = response.loadId,
            loading = false,
            initialized = true,
            error = false,
        )
        markChanged()
    }

    @StoreThread
    internal fun handleSearchError(requestId: Long) {
        if (requestId != activeSearchRequest) return
        search = search.copy(loading = false, initialized = true, error = true)
        markChanged()
    }

    @StoreThread
    internal fun handleDetail(applicationId: Long, requestId: Long, application: ApplicationDirectoryApplication) {
        if (detailRequests[applicationId] != requestId) return
        details[applicationId] = DetailState.Loaded(application)
        markChanged()
    }

    @StoreThread
    internal fun handleDetailError(applicationId: Long, requestId: Long) {
        if (detailRequests[applicationId] != requestId) return
        details[applicationId] = DetailState.Error
        markChanged()
    }

    override fun snapshotData() {
        snapshot = State(collections, categories, categoriesLoading, categoriesError, search, details.toMap())
    }

    companion object {
        const val FEATURED_CATEGORY_ID = 0
        const val MAX_QUERY_LENGTH = 100
    }
}
