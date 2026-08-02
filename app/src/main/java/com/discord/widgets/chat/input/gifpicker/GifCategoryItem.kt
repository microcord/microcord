package com.discord.widgets.chat.input.gifpicker

import com.discord.models.gifpicker.domain.ModelGifCategory
import com.discord.utilities.recycler.DiffKeyProvider
import java.io.Serializable

/**
 * A tile on the GIF picker's landing grid.
 *
 * `Serializable` because [WidgetGifCategory] receives the tapped tile through fragment arguments.
 */
sealed class GifCategoryItem : DiffKeyProvider, Serializable {

    override val key: String
        get() = hashCode().toString()

    /** A Tenor category; tapping it searches for [ModelGifCategory.categoryName]. */
    data class Standard(val gifCategory: ModelGifCategory) : GifCategoryItem()

    /** User-starred GIFs, always first even when the list is empty. */
    data class Favorite(val gifPreviewUrl: String) : GifCategoryItem()

    /** Locally ranked recent/frequent GIFs. */
    data class Frequent(val gifPreviewUrl: String) : GifCategoryItem()

    /** The synthetic first tile. It has no category name — it maps to the trending endpoint. */
    data class Trending(val gifPreviewUrl: String) : GifCategoryItem()
}
