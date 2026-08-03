package com.discord.stores

import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResponse
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResult
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.stores.updates.ObservationDeck
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.Schedulers

class ApplicationDirectoryStoreTest {
    private data class Harness(val store: StoreApplicationDirectory, val dispatcher: Dispatcher) {
        fun mutate(block: StoreApplicationDirectory.() -> Unit) = dispatcher.schedule { store.block() }
    }

    private fun store(): Harness {
        val dispatcher = Dispatcher(Schedulers.trampoline())
        val deck = ObservationDeck()
        val store = StoreApplicationDirectory(dispatcher, deck, api = { null })
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        return Harness(store, dispatcher)
    }

    @Test
    fun fixtureParsesTypedDirectoryAndInstallConfiguration() {
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
        val json = requireNotNull(javaClass.getResource("/fixtures/application_directory_search.json")).readText()
        val response = gson.fromJson(json, ApplicationDirectorySearchResponse::class.java)
        val app = response.results.single().data

        assertEquals("8589934592", app.flagsNew)
        assertEquals(setOf(0, 1), app.supportedIntegrationTypes)
        assertEquals(listOf("applications.commands"), app.installParams(1)?.scopes)
        assertEquals("Detailed sanitized fixture description.", app.directoryEntry?.detailedDescription)
        assertEquals(1, response.countsByCategory[5])
    }

    @Test
    fun pagesMergeByApplicationIdAndPreserveServerRanking() {
        val harness = store()
        val store = harness.store
        store.search("tools")
        harness.mutate { handleSearchSuccess(1, 1, response(3, app(2), app(1))) }
        store.search("tools", page = 2)
        harness.mutate { handleSearchSuccess(2, 2, response(3, app(1), app(3))) }

        assertEquals(listOf(2L, 1L, 3L), store.getState().search.applications.map { it.id })
        assertTrue(store.getState().search.canLoadMore)
    }

    @Test
    fun staleSearchAndDetailResponsesAreIgnored() {
        val harness = store()
        val store = harness.store
        store.search("old")
        store.search("new")
        harness.mutate { handleSearchSuccess(1, 1, response(1, app(1))) }
        harness.mutate { handleSearchSuccess(2, 1, response(1, app(2))) }
        assertEquals(listOf(2L), store.getState().search.applications.map { it.id })

        val detailHarness = store()
        val detailStore = detailHarness.store
        detailStore.loadApplication(9)
        detailStore.loadApplication(9, force = true)
        detailHarness.mutate { handleDetail(9, 1, app(1)) }
        detailHarness.mutate { handleDetail(9, 2, app(2)) }
        val loaded = detailStore.getState().details[9] as StoreApplicationDirectory.DetailState.Loaded
        assertEquals(2L, loaded.application.id)
    }

    @Test
    fun failureAndEmptyAreDistinctTerminalStates() {
        val harness = store()
        val store = harness.store
        store.search("none")
        assertTrue(store.getState().search.error)
        assertFalse(store.getState().search.empty)
        harness.mutate { handleSearchSuccess(1, 1, response(1)) }
        assertTrue(store.getState().search.empty)
        assertFalse(store.getState().search.error)
    }

    private fun app(id: Long) = ApplicationDirectoryApplication(id = id, name = "App $id")

    private fun response(totalPages: Int, vararg apps: ApplicationDirectoryApplication) =
        ApplicationDirectorySearchResponse(
            results = apps.map { ApplicationDirectorySearchResult(1, it) },
            numPages = totalPages,
            type = 1,
            loadId = "load",
            resultCount = apps.size,
        )
}
