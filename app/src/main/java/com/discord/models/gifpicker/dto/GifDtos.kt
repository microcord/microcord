package com.discord.models.gifpicker.dto

/** Wire shape of one GIF returned by the Tenor-backed `gifs` endpoints. */
data class GifDto(
    val src: String,
    val url: String,
    val width: Int,
    val height: Int,
)

/** Wire shape of a GIF search category (`gifs/trending`). */
data class GifCategoryDto(
    val name: String,
    val src: String,
)

/** A category tile's preview image; only the image source comes back. */
data class TrendingGifPreviewDto(val src: String)

data class TrendingGifsResponseDto(
    val categories: List<GifCategoryDto>,
    val gifs: List<TrendingGifPreviewDto>,
)

data class TrendingGifCategoriesResponseDto(
    val categories: List<GifCategoryDto>,
    val gifs: List<TrendingGifPreviewDto>,
)

/**
 * A GIF ready for the picker grid.
 *
 * [gifImageUrl] is the renderable image; [tenorGifUrl] is the link that gets sent as message
 * content when the GIF is picked.
 */
data class ModelGif(
    val gifImageUrl: String,
    val tenorGifUrl: String,
    val width: Int,
    val height: Int,
) {
    companion object {
        fun createFromGifDto(gifDto: GifDto): ModelGif =
            ModelGif(gifDto.src, gifDto.url, gifDto.width, gifDto.height)
    }
}
