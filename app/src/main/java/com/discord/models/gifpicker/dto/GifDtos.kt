package com.discord.models.gifpicker.dto

import com.discord.utilities.embed.EmbedResourceUtils

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

        /** A Tenor video, bare or behind the media proxy; the id is 11 characters, then 5 naming the rendition. */
        private val TENOR_VIDEO = Regex("""media\.tenor\.com/([\w-]{11})[\w-]{5}/([^/?]+)\.(?:mp4|webm)""")
        private val DISCORD_PROXY = Regex("""^https://(media|images-ext-\d+)\.discordapp\.net/""")

        /**
         * What a picker tile loads for [src], best first.
         *
         * Favorites keep whatever was favorited: often a Tenor MP4, which an image view cannot draw,
         * or a full-size GIF attachment. Tenor serves every rendition from the same id, so a video
         * becomes its tiny animated WebP (`AAAA1`); anything on the media proxy goes through the
         * chat's sizing, which makes a GIF a small animated WebP and anything else a still.
         */
        fun previewUrls(src: String, width: Int, height: Int): List<String> {
            if (src.isBlank()) return emptyList()
            TENOR_VIDEO.find(src)?.let { match ->
                val (id, name) = match.destructured
                return listOf("https://media.tenor.com/${id}AAAA1/$name.webp")
            }
            if (width <= 0 || height <= 0 || !DISCORD_PROXY.containsMatchIn(src)) return listOf(src)
            return EmbedResourceUtils.getPreviewUrls(src, width, height)
        }
    }
}
