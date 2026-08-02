package com.discord.widgets.chat.input.gifpicker

import androidx.annotation.StringRes
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.utilities.recycler.DiffKeyProvider

/**
 * One row of the GIF grid.
 *
 * The search screen mixes GIF tiles with a single full-width "suggested terms" block, so both are
 * modelled as the same adapter item type and separated by [ViewType].
 */
sealed class GifAdapterItem(val type: ViewType) : DiffKeyProvider {

    /**
     * A single GIF tile. [query] is the search that produced it and is carried purely so that
     * re-running the same search does not diff as "unchanged" against a different result set.
     */
    data class GifItem(
        val gif: ModelGif,
        val query: String? = "",
        val isFavorite: Boolean = false,
    ) :
        GifAdapterItem(ViewType.VIEW_TYPE_GIF_ITEM) {
        override val key: String = hashCode().toString()
    }

    /**
     * The suggested/related search chips. Two variants because the empty-results form is centred
     * with an icon and fills the viewport, while the non-empty form is a footer under the grid.
     */
    sealed class SuggestedTermsItem(
        open val terms: List<String>,
        viewType: ViewType,
        @StringRes open val titleResId: Int,
    ) : GifAdapterItem(viewType) {

        override val key: String
            get() = (terms.joinToString() + titleResId).hashCode().toString()

        data class SuggestedTermsEmptyResults(
            override val terms: List<String>,
            @StringRes override val titleResId: Int,
        ) : SuggestedTermsItem(terms, ViewType.VIEW_TYPE_GIF_SEARCH_TERMS_EMPTY_RESULTS, titleResId)

        data class SuggestedTermsNonEmptyResults(
            override val terms: List<String>,
            @StringRes override val titleResId: Int,
        ) : SuggestedTermsItem(terms, ViewType.VIEW_TYPE_GIF_SEARCH_TERMS_NON_EMPTY_RESULTS, titleResId)
    }

    /** Ordinals are the recycler's view types; declaration order is load-bearing. */
    enum class ViewType {
        VIEW_TYPE_GIF_ITEM,
        VIEW_TYPE_GIF_SEARCH_TERMS_EMPTY_RESULTS,
        VIEW_TYPE_GIF_SEARCH_TERMS_NON_EMPTY_RESULTS;

        companion object {
            private val cachedValues = values()

            fun fromInt(index: Int): ViewType = cachedValues[index]
        }
    }
}
