package com.discord.widgets.chat.input.gifpicker

import com.discord.R
import com.discord.models.gifpicker.domain.ModelGifCategory
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.GifItem
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.SuggestedTermsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GIF picker's two list-building rules.
 *
 * Both fail silently on device — a missing trending tile looks like a slow network, and the wrong
 * empty-state string looks like a copy choice — so they are pinned here.
 */
class GifPickerItemsTest {

    private fun gif(id: Int) = ModelGif("https://img/$id.gif", "https://tenor/$id", 100, 50)

    // region landing grid

    @Test
    fun trendingTileLeadsAndOnlyAppearsOnceItsPreviewHasArrived() {
        val categories = listOf(
            ModelGifCategory("Reactions", "https://p/1"),
            ModelGifCategory("Animals", "https://p/2"),
        )

        val withoutPreview = GifPickerViewModel.buildCategoryItems(
            GifPickerViewModel.StoreState(categories, trendingGifCategoryPreviewUrl = ""),
        )
        assertEquals(
            listOf("Reactions", "Animals"),
            withoutPreview.filterIsInstance<GifCategoryItem.Standard>().map { it.gifCategory.categoryName },
        )
        assertTrue(withoutPreview.none { it is GifCategoryItem.Trending })

        val withPreview = GifPickerViewModel.buildCategoryItems(
            GifPickerViewModel.StoreState(categories, trendingGifCategoryPreviewUrl = "https://trend"),
        )
        assertEquals(GifCategoryItem.Trending("https://trend"), withPreview.first())
        assertEquals(categories.size + 1, withPreview.size)
    }

    // endregion

    // region search grid

    @Test
    fun resultsAreFollowedByRelatedSearches() {
        val items = GifSearchViewModel.buildAdapterItems(
            GifSearchViewModel.StoreState.SearchResults(
                gifs = listOf(gif(1), gif(2)),
                suggested = listOf("cats", "dogs"),
                trending = listOf("trending"),
                searchQuery = "pets",
            ),
            hasQuery = true,
        )

        assertEquals(2, items.count { it is GifItem })
        assertEquals(listOf("pets", "pets"), items.filterIsInstance<GifItem>().map { it.query })
        val footer = items.last() as SuggestedTermsItem.SuggestedTermsNonEmptyResults
        assertEquals(R.string.gif_picker_related_search, footer.titleResId)
        assertEquals(listOf("cats", "dogs"), footer.terms)
    }

    @Test
    fun anEmptyResultWithSuggestionsOffersThemAndSaysSo() {
        val items = GifSearchViewModel.buildAdapterItems(
            GifSearchViewModel.StoreState.SearchResults(
                gifs = emptyList(),
                suggested = listOf("cats"),
                trending = listOf("trending"),
                searchQuery = "qqqq",
            ),
            hasQuery = true,
        )

        val only = items.single() as SuggestedTermsItem.SuggestedTermsEmptyResults
        assertEquals(R.string.no_gif_search_results_with_related_search, only.titleResId)
    }

    @Test
    fun anEmptyResultWithNoSuggestionsUsesTheStandaloneCopy() {
        val items = GifSearchViewModel.buildAdapterItems(
            GifSearchViewModel.StoreState.SearchResults(
                gifs = emptyList(),
                suggested = emptyList(),
                trending = listOf("trending"),
                searchQuery = "qqqq",
            ),
            hasQuery = true,
        )

        val only = items.single() as SuggestedTermsItem.SuggestedTermsEmptyResults
        assertEquals(R.string.no_gif_search_results_without_related_search, only.titleResId)
    }

    /**
     * The store can report a cached empty result for the *previous* query after the field has been
     * cleared; the copy must follow the field, not the store.
     */
    @Test
    fun aClearedFieldFallsBackToThePromptEvenWhenTheStoreStillReportsAQuery() {
        val items = GifSearchViewModel.buildAdapterItems(
            GifSearchViewModel.StoreState.SearchResults(
                gifs = emptyList(),
                suggested = emptyList(),
                trending = listOf("trending"),
                searchQuery = "stale",
            ),
            hasQuery = false,
        )

        val only = items.single() as SuggestedTermsItem.SuggestedTermsEmptyResults
        assertEquals(R.string.gif_picker_enter_search, only.titleResId)
    }

    @Test
    fun anEmptyFieldShowsTheTrendingTerms() {
        val items = GifSearchViewModel.buildAdapterItems(
            GifSearchViewModel.StoreState.TrendingSearchTermsResults(listOf("a", "b")),
            hasQuery = false,
        )

        val only = items.single() as SuggestedTermsItem.SuggestedTermsEmptyResults
        assertEquals(R.string.gif_picker_enter_search, only.titleResId)
        assertEquals(listOf("a", "b"), only.terms)
    }

    // endregion

    /**
     * The two chip blocks carry the same terms but different copy, so keying them on the terms
     * alone would make a switch between them diff as "unchanged" and leave the wrong text on screen.
     */
    @Test
    fun suggestedTermBlocksWithDifferentCopyHaveDifferentDiffKeys() {
        val terms = listOf("cats")
        val withResults = SuggestedTermsItem.SuggestedTermsNonEmptyResults(
            terms,
            R.string.gif_picker_related_search,
        )
        val withoutResults = SuggestedTermsItem.SuggestedTermsEmptyResults(
            terms,
            R.string.gif_picker_enter_search,
        )

        assertNotEquals(withResults.key, withoutResults.key)
    }
}
