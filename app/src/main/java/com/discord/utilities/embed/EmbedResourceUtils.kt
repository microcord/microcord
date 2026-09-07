package com.discord.utilities.embed

import android.content.Context
import android.content.res.Resources
import androidx.annotation.DrawableRes
import com.discord.R
import com.discord.api.message.attachment.MessageAttachment
import com.discord.utilities.images.MediaProxyUrl
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.embed.EmbedImage
import com.discord.api.message.embed.EmbedThumbnail
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedVideo
import com.discord.api.message.embed.MessageEmbed
import com.discord.embed.RenderableEmbedMedia
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.string.STATIC_IMAGE_EXTENSION
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything the chat list needs to decide *what* an embed or attachment looks like: which of its
 * media slots to preview, whether that preview animates, how large to draw it, and which CDN
 * variants to ask for.
 *
 * Purely derivational — nothing here touches a store, a view or the network, which is what lets the
 * same functions serve the chat row, the media viewer and the reply preview.
 */
object EmbedResourceUtils {

    /** Scheme for a media URI that is really a local drawable resource id. */
    const val FILE_SCHEME: String = "res:///"

    /** Widest rendition the CDN is asked for, however wide the screen is. */
    const val MAX_IMAGE_SIZE: Int = 1440

    /**
     * Embed and attachment media never grows taller than this, however tall the source is.
     *
     * Lazy because it reads the system display metrics: resolving it during class init would make
     * every other function here unusable from a plain JVM unit test.
     */
    val MAX_IMAGE_VIEW_HEIGHT_PX: Int by lazy { dpToPixels(320) }

    private val PLAYABLE_EMBED_TYPES: List<EmbedType> = listOf(EmbedType.VIDEO, EmbedType.GIFV)

    /** Modern multi-image unfurls arrive as a rich card followed by image-only same-URL embeds. */
    fun MessageEmbed.getGalleryImages(embeds: List<MessageEmbed>): List<EmbedImage> {
        if (type != EmbedType.RICH || video != null) return emptyList()
        val companions = embeds.filter { candidate ->
            url != null && candidate.url == url && candidate.type == EmbedType.RICH && candidate.video == null &&
                candidate.title.isNullOrEmpty() && candidate.description.isNullOrEmpty() &&
                candidate.author == null && candidate.provider == null && candidate.footer == null &&
                candidate.thumbnail == null && candidate.fields.isNullOrEmpty()
        }.mapNotNull { it.image }
        return (listOfNotNull(image) + companions)
            .filter { !it.url.isNullOrBlank() || !it.proxyUrl.isNullOrBlank() }
            .distinctBy { it.url?.takeIf(String::isNotBlank) ?: it.proxyUrl }
    }

    /**
     * Which of an embed's three media slots supplies its still preview.
     *
     * Playable embeds preview from their thumbnail and fall back to the video itself; articles,
     * images and tweets only ever preview from the thumbnail; link and HTML embeds have no preview
     * at all; everything else previews from the image slot.
     */
    fun MessageEmbed.getPreviewImage(): RenderableEmbedMedia? = when {
        isPlayable() -> thumbnail?.asRenderableEmbedMedia() ?: video?.asRenderableEmbedMedia()
        type == EmbedType.ARTICLE || type == EmbedType.IMAGE || type == EmbedType.TWEET ->
            thumbnail?.asRenderableEmbedMedia()
        type == EmbedType.LINK || type == EmbedType.HTML -> null
        else -> image?.asRenderableEmbedMedia()
    }

    /** Whether tapping the embed should start playback rather than open a still viewer. */
    fun MessageEmbed.isPlayable(): Boolean = type in PLAYABLE_EMBED_TYPES ||
        (type == EmbedType.RICH && !(video?.proxyUrl ?: video?.url).isNullOrBlank())

    /**
     * Whether the embed is nothing but media, and so renders without a card.
     *
     * A GIFV always qualifies. An image or video qualifies only when it carries neither an author
     * nor a title — anything with those has text to show and needs the card.
     */
    fun MessageEmbed.isInlineEmbed(): Boolean {
        if (getPreviewImage() == null && video == null) return false
        return when (type) {
            EmbedType.IMAGE, EmbedType.VIDEO -> author == null && title == null
            EmbedType.GIFV -> true
            else -> false
        }
    }

    /**
     * An inline embed that is also *still* media — the case that can be rendered as a bare image
     * with no player chrome. A video is inline but never simple.
     */
    fun MessageEmbed.isSimpleEmbed(): Boolean = when (type) {
        EmbedType.IMAGE, EmbedType.GIFV -> isInlineEmbed()
        else -> false
    }

    /**
     * Where tapping the embed's picture should take the user, or null to open the media viewer.
     *
     * Video embeds open the source video; file embeds open the provider that served them.
     */
    fun MessageEmbed.getExternalOpenUrl(): String? = when (type) {
        EmbedType.VIDEO -> video?.url
        EmbedType.FILE -> provider?.url
        else -> null
    }

    /** True for media that moves: a GIFV, or an image whose URL is a `.gif`. */
    fun isAnimated(embedType: EmbedType?, previewImageUri: String?): Boolean = when (embedType) {
        EmbedType.GIFV -> true
        EmbedType.IMAGE ->
            previewImageUri?.lowercase(Locale.ROOT)?.contains(".gif") == true
        else -> false
    }

    fun MessageEmbed.isAnimated(): Boolean = isAnimated(type, getPreviewImage()?.url)

    /**
     * The CDN variants to try for a preview, best first.
     *
     * A still request is preferred because it is far smaller, but a URL that is already a local
     * resource or an animating `.gif` is requested as-is when [shouldAnimate]. The unsized original
     * is always kept as the fallback, in case the media proxy declines to resize it.
     */
    @JvmOverloads
    fun getPreviewUrls(
        originalUrl: String,
        width: Int,
        height: Int,
        shouldAnimate: Boolean = true,
    ): List<String> {
        val sized = "$originalUrl${if ('?' in originalUrl) '&' else '?'}width=$width&height=$height"
        // CDN URLs carry signed query strings (`...catsurf.gif?ex=…`), so the extension has to be
        // read from the path alone — an endsWith on the full URL never matched, every animated GIF
        // was downgraded to a still, and only the fullscreen viewer animated them.
        val barePath = originalUrl.substringBefore('?').lowercase(Locale.ROOT)
        val keepAnimated = shouldAnimate &&
            (originalUrl.startsWith(FILE_SCHEME) || barePath.endsWith(".gif"))
        return if (keepAnimated) {
            listOf(sized)
        } else {
            listOf("$sized&format=$STATIC_IMAGE_EXTENSION", sized)
        }
    }

    /**
     * How wide media may be drawn: the chat column, capped at the largest size the CDN serves.
     *
     * The reference measures the column with `DisplayUtils.getScreenSize`, which this project does
     * not have; the display metrics of the passed context are equivalent for a full-width activity
     * and differ only in multi-window, where the metrics are the more accurate of the two.
     */
    fun computeMaximumImageWidthPx(context: Context): Int {
        val resources = context.resources
        val gutters = resources.getDimensionPixelSize(R.dimen.uikit_guideline_chat) +
            resources.getDimensionPixelSize(R.dimen.chat_cell_horizontal_spacing_total)
        return min(MAX_IMAGE_SIZE, resources.displayMetrics.widthPixels - gutters)
    }

    /**
     * Fits an image of [actualWidth] x [actualHeight] into the [maxWidth] x [maxHeight] box while
     * preserving its aspect ratio.
     *
     * Three outcomes. An image that would occupy more than half the box in either direction is
     * scaled *down* to fit it exactly, with the longer side pinned to the box and the other side
     * clamped afterwards. A narrow image, when a [minWidth] is given, is scaled *up* to that width
     * and then clamped to the height. Anything else is drawn at its intrinsic size in density
     * pixels — a small image is not stretched to fill the column.
     *
     * The "more than half" test is why a modest image keeps its native size: the source dimensions
     * are authored in dp, so scaling them by density and comparing against half the box is what
     * separates "a thumbnail" from "a photo".
     */
    @JvmOverloads
    fun calculateScaledSize(
        actualWidth: Int,
        actualHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        resources: Resources,
        minWidth: Int = 0,
    ): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val densityWidth = density * actualWidth
        val densityHeight = density * actualHeight
        val aspectRatio = if (actualHeight > 0) actualWidth.toFloat() / actualHeight else 0f

        val needsDownscale = densityWidth > maxWidth / 2f || densityHeight > maxHeight / 2f
        val needsUpscale = minWidth > 0 && actualWidth < minWidth

        return when {
            needsDownscale -> {
                val isLandscape = actualWidth > actualHeight
                var width = if (isLandscape) maxWidth.toFloat() else maxHeight * aspectRatio
                var height = if (isLandscape) maxWidth / aspectRatio else maxHeight.toFloat()
                if (isLandscape && height > maxHeight) {
                    width *= maxHeight / height
                    height = maxHeight.toFloat()
                } else if (!isLandscape && width > maxWidth) {
                    height *= maxWidth / width
                    width = maxWidth.toFloat()
                }
                width.roundToInt() to height.roundToInt()
            }
            needsUpscale -> {
                var width = minWidth.toFloat()
                var height = minWidth.toFloat() / actualWidth * actualHeight
                if (height > maxHeight) {
                    width *= maxHeight / height
                    height = maxHeight.toFloat()
                }
                width.roundToInt() to height.roundToInt()
            }
            else -> densityWidth.roundToInt() to densityHeight.roundToInt()
        }
    }

    /**
     * Renderable media for an attachment: images and videos preview from the CDN proxy, everything
     * else becomes a file-type glyph.
     */
    fun createRenderableEmbedMediaFromAttachment(
        attachment: MessageAttachment,
    ): RenderableEmbedMedia = when (attachment.type) {
        MessageAttachmentType.VIDEO, MessageAttachmentType.IMAGE -> RenderableEmbedMedia(
            MediaProxyUrl.forAttachment(attachment, attachment.proxyUrl),
            attachment.width,
            attachment.height,
        )
        MessageAttachmentType.FILE -> createFileRenderableMedia(attachment.filename)
    }

    /**
     * The file glyph as renderable media.
     *
     * The 30x40 size is the glyph's own aspect ratio in dp, not a measurement of the file — a file
     * card sizes to its icon, not to its content.
     */
    private fun createFileRenderableMedia(filename: String?): RenderableEmbedMedia =
        RenderableEmbedMedia("$FILE_SCHEME${getFileDrawable(filename)}", 30, 40)

    /** The icon shown for a non-previewable attachment, chosen from its filename extension. */
    @DrawableRes
    fun getFileDrawable(filename: String?): Int {
        val extension = filename?.substringAfterLast('.', "").orEmpty()
        return FileType.getFromExtension(extension)?.fileDrawable ?: R.drawable.ic_file_unknown
    }

    /**
     * The proxy URL is preferred over the origin: it is the only one the CDN will resize, and the
     * only one guaranteed to be reachable from a client behind a restrictive network.
     */
    internal fun EmbedImage.asRenderableEmbedMedia() =
        RenderableEmbedMedia((proxyUrl?.takeIf(String::isNotBlank) ?: url)?.let(MediaProxyUrl::forMedia), width, height)

    private fun EmbedThumbnail.asRenderableEmbedMedia() =
        RenderableEmbedMedia((proxyUrl ?: url)?.let(MediaProxyUrl::forMedia), width, height)

    private fun EmbedVideo.asRenderableEmbedMedia() =
        RenderableEmbedMedia(proxyUrl ?: url, width, height)
}
