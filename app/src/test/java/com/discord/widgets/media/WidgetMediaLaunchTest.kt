package com.discord.widgets.media

import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.embed.EmbedImage
import com.discord.api.message.embed.EmbedThumbnail
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedVideo
import com.discord.api.message.embed.MessageEmbed
import com.discord.player.MediaType
import com.discord.utilities.icon.IconUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decisions [WidgetMedia] makes before it builds its launch intent.
 *
 * These are the ones that determine whether a tapped image opens as a still, plays as a video, or
 * silently opens nothing at all.
 */
class WidgetMediaLaunchTest {

    @Test
    fun `resuming video omits the opening poster but fresh video and stills keep it`() {
        val video = launchArgs(attachment("https://example.com/clip.mp4"))
        assertFalse(video.showPreviewAt(42_345L))
        assertTrue(video.showPreviewAt(0L))
        assertTrue(video.showPreviewAt(-1L))
        assertTrue(launchArgs(attachment("https://example.com/image.png")).showPreviewAt(42_345L))
        assertTrue(video.copy(progressiveMediaUri = null).showPreviewAt(42_345L))
    }

    @Test
    fun `inline position applies once and never leaks to another gallery video`() {
        val state = WidgetMediaViewModel(42_345L)
        state.selectMedia("https://example.com/first.mp4")
        assertEquals(42_345L, state.currentPlayerPositionMs)
        assertTrue(state.isPlaying)

        state.currentPlayerPositionMs = 58_901L
        state.isPlaying = false
        state.showCoverFrame = false
        state.selectMedia("https://example.com/first.mp4")
        assertEquals(58_901L, state.currentPlayerPositionMs)
        assertFalse(state.isPlaying)
        assertFalse(state.showCoverFrame)

        state.selectMedia("https://example.com/second.mp4")
        assertEquals(0L, state.currentPlayerPositionMs)
        assertTrue(state.isPlaying)
        state.selectMedia("https://example.com/first.mp4")
        assertEquals(0L, state.currentPlayerPositionMs)
        assertEquals(0L, WidgetMediaViewModel(-1L).currentPlayerPositionMs)
        assertEquals(0L, WidgetMediaViewModel().currentPlayerPositionMs)
    }

    @Test
    fun `profile image opens as the original fullscreen still`() {
        val args = launchArgs("https://cdn.discordapp.com/avatars/1/hash.webp?size=2048", "user")

        assertEquals(args.uri, args.previewImageUri)
        assertEquals("https://cdn.discordapp.com/avatars/1/hash.webp?quality=lossless&size=1024", args.uri)
        assertEquals(3072, args.width)
        assertEquals(3072, args.height)
        assertEquals("user", args.title)
        assertNull(args.progressiveMediaUri)
        assertNull(args.mediaType)
    }

    @Test
    fun `profile quality replaces only quality and size`() {
        assertEquals(
            "https://cdn.discordapp.com/banner.webp?hash=value&quality=lossless&size=1024#frame",
            profileImageUrl("https://cdn.discordapp.com/banner.webp?size=128&hash=value&quality=low#frame"),
        )
    }

    private fun attachment(url: String, width: Int? = 800, height: Int? = 600) = MessageAttachment(
        url = url,
        size = 1024L,
        id = 1L,
        proxyUrl = "https://media.discordapp.net/proxied",
        filename = url.substringAfterLast('/'),
        width = width,
        height = height,
    )

    private fun embed(
        type: EmbedType?,
        url: String? = "https://example.com/page",
        title: String? = "Title",
        video: EmbedVideo? = null,
        thumbnail: EmbedThumbnail? = null,
    ) = MessageEmbed(
        title = title,
        type = type,
        description = null,
        url = url,
        timestamp = null,
        color = null,
        footer = null,
        image = null,
        thumbnail = thumbnail,
        video = video,
        provider = null,
        author = null,
        fields = null,
    )

    @Test
    fun `image attachment carries no playable stream`() {
        val args = launchArgs(attachment("https://cdn.discordapp.com/a/cat.png"))

        assertNull(args.progressiveMediaUri)
        assertNull(args.mediaType)
        assertEquals("https://media.discordapp.net/proxied", args.previewImageUri)
        assertEquals("https://media.discordapp.net/proxied", args.uri)
        assertEquals("cat.png", args.title)
        assertEquals(800, args.width)
        assertEquals(600, args.height)
    }

    @Test
    fun `video attachment plays its proxy url and still previews from it`() {
        val args = launchArgs(attachment("https://cdn.discordapp.com/a/clip.mp4"))

        assertEquals(MediaType.VIDEO, args.mediaType)
        assertEquals("https://media.discordapp.net/proxied", args.progressiveMediaUri)
        assertEquals("https://media.discordapp.net/proxied?format=webp&width=800&height=600", args.previewImageUri)
        assertEquals("https://media.discordapp.net/proxied", args.uri)
        assertEquals("clip.mp4", args.title)
        assertEquals(800, args.width)
        assertEquals(600, args.height)
    }

    @Test
    fun `content type identifies extensionless media and wins over URL text`() {
        val media = attachment("https://example.com/stream?poster=cover.png")
        for (mime in listOf("video/mp4", "VIDEO/WEBM", "video/x-matroska")) {
            val args = launchArgs(media.copy(contentType = mime))
            assertEquals(MediaType.VIDEO, args.mediaType)
            assertEquals(media.proxyUrl, args.progressiveMediaUri)
        }
        val image = launchArgs(attachment("https://example.com/clip.mp4").copy(contentType = "image/webp"))
        assertNull(image.mediaType)
        assertNull(image.progressiveMediaUri)
    }

    @Test
    fun `video poster preserves the stream aspect ratio`() {
        assertEquals(
            "https://media.discordapp.net/proxied?format=webp&width=405&height=720",
            mediaVideoPreviewUrl("https://media.discordapp.net/proxied", 1080, 1920),
        )
    }

    @Test
    fun `gifv embed loops muted and streams the proxied video`() {
        val args = launchArgs(
            embed(
                type = EmbedType.GIFV,
                video = EmbedVideo(url = "https://tenor.com/v.mp4", proxyUrl = "https://proxy/v.mp4", height = 200, width = 300),
                thumbnail = EmbedThumbnail(url = "https://tenor.com/t.png", proxyUrl = "https://proxy/t.png", height = 200, width = 300),
            ),
        )

        assertEquals(MediaType.GIFV, args.mediaType)
        assertEquals("https://proxy/v.mp4", args.progressiveMediaUri)
        assertEquals("https://proxy/t.png", args.previewImageUri)
        assertEquals(300, args.width)
        assertEquals(200, args.height)
    }

    @Test
    fun `video embed falls back to the unproxied video url`() {
        val args = launchArgs(
            embed(
                type = EmbedType.VIDEO,
                video = EmbedVideo(url = "https://host/v.mp4", proxyUrl = null, height = 90, width = 160),
            ),
        )

        assertEquals(MediaType.VIDEO, args.mediaType)
        assertEquals("https://host/v.mp4", args.progressiveMediaUri)
    }

    @Test
    fun `a rich embed plays its extensionless video using the proxy or origin`() {
        val origin = "https://example.com/stream/opaque-id"
        for (proxy in listOf("https://proxy.example.com/stream/opaque-id", null)) {
            val args = launchArgs(
                embed(
                    type = EmbedType.RICH,
                    video = EmbedVideo(origin, proxy, 800, 1152),
                    thumbnail = EmbedThumbnail("https://example.com/poster.jpg", "https://proxy.example.com/poster.jpg", 800, 1152),
                ),
            )

            assertEquals(MediaType.VIDEO, args.mediaType)
            assertEquals(proxy ?: origin, args.progressiveMediaUri)
            assertEquals("https://proxy.example.com/poster.jpg", args.previewImageUri)
            assertEquals("https://example.com/page", args.uri)
            assertEquals(1152, args.width)
            assertEquals(800, args.height)
        }
    }

    @Test
    fun `image embed opens as a still even though its declared type is playable-adjacent`() {
        val args = launchArgs(
            embed(
                type = EmbedType.IMAGE,
                thumbnail = EmbedThumbnail(url = "https://host/i.png", proxyUrl = "https://proxy/i.png", height = 480, width = 640),
            ),
        )

        // No stream, so the viewer never builds a MediaSource and stays on the still path.
        assertNull(args.progressiveMediaUri)
        assertEquals("https://proxy/i.png", args.previewImageUri)
    }

    @Test
    fun `non playable embed cannot accidentally create a media source`() {
        val args = launchArgs(
            embed(
                type = EmbedType.RICH,
                thumbnail = EmbedThumbnail("https://host/i.png", "https://proxy/i.png", 480, 640),
            ),
        )

        assertNull(args.progressiveMediaUri)
        assertEquals(MediaType.VIDEO, args.mediaType)
    }

    @Test
    fun `embed without its own link shares the preview image instead`() {
        val args = launchArgs(
            embed(
                type = EmbedType.IMAGE,
                url = null,
                thumbnail = EmbedThumbnail(url = "https://host/i.png", proxyUrl = "https://proxy/i.png", height = 480, width = 640),
            ),
        )

        // WidgetMedia.launch substitutes the preview when `uri` is absent; assert the input to that.
        assertNull(args.uri)
        assertEquals("https://proxy/i.png", args.previewImageUri)
    }

    @Test
    fun `four embed gallery images keep their order dimensions and selected identity as stills`() {
        val images = listOf(
            EmbedImage("https://example.com/1.png", "https://proxy.example.com/1.png?quality=lossless", 800, 600),
            EmbedImage("https://example.com/2.jpg?format=jpg&name=a%2Fb", null, 600, 900),
            EmbedImage("https://example.com/3.webp?quality=lossless", " ", 1024, 512),
            EmbedImage(null, "https://proxy.example.com/4.avif?quality=lossless&format=avif", 400, 800),
        )
        val embed = embed(
            type = EmbedType.RICH,
            url = "https://example.com/page?view=gallery",
            video = EmbedVideo("https://example.com/unused.mp4", null, 100, 200),
            thumbnail = EmbedThumbnail("https://example.com/unused.png", null, 100, 200),
        ).copy(image = images.first())

        for (selectedIndex in images.indices) {
            val (items, selected) = embedGalleryArgs(embed, images, selectedIndex)

            assertEquals(selectedIndex, selected)
            assertEquals(
                listOf(
                    "https://proxy.example.com/1.png?quality=lossless",
                    "https://example.com/2.jpg?format=jpg&name=a%2Fb",
                    "https://example.com/3.webp?quality=lossless",
                    "https://proxy.example.com/4.avif?quality=lossless&format=${IconUtils.staticImageExtension}",
                ),
                items.map { it.args.previewImageUri },
            )
            assertEquals(images.map { it.width to it.height }, items.map { it.args.width to it.args.height })
            items.forEach { item ->
                assertEquals(embed.title, item.args.title)
                assertEquals(embed.url, item.args.uri)
                assertNull(item.args.progressiveMediaUri)
                assertNull(item.args.mediaType)
                assertNull(item.viewerContext)
            }
        }
    }

    @Test
    fun `URL-less gallery entries are skipped without shifting the selected image`() {
        val missing = EmbedImage(null, null, null, null)
        val images = listOf(
            missing,
            EmbedImage("https://example.com/1.png", null, 800, 600),
            missing.copy(url = " ", proxyUrl = ""),
            EmbedImage("https://example.com/2.png", null, 600, 800),
            missing,
        )
        val embed = embed(EmbedType.RICH)
        for ((requested, expected) in listOf(-1 to 0, 0 to 0, 1 to 0, 2 to 1, 3 to 1, 4 to 1, Int.MAX_VALUE to 1)) {
            val (items, selected) = embedGalleryArgs(embed, images, requested)

            assertEquals(expected, selected)
            assertEquals(listOf("https://example.com/1.png", "https://example.com/2.png"), items.map { it.args.previewImageUri })
        }
        assertEquals(emptyList<MediaGalleryItem>() to 0, embedGalleryArgs(embed, emptyList(), 0))
        assertEquals(emptyList<MediaGalleryItem>() to 0, embedGalleryArgs(embed, listOf(missing), 0))
    }

    @Test
    fun `gallery without a source page falls back to each image for sharing`() {
        val images = listOf(EmbedImage("https://example.com/image.png?quality=lossless", "", null, null))
        for (page in listOf(null, " ")) {
            val (items, selected) = embedGalleryArgs(embed(EmbedType.RICH, url = page), images, 0)
            val args = items[selected].args

            // Both intentFor and bindMedia substitute this item's preview when the source is absent.
            assertNull(args.uri)
            assertEquals(images.single().url, args.previewImageUri)
            assertNull(args.width)
            assertNull(args.height)
        }
    }

    @Test
    fun `still requests are re-encoded but animated gifs are fetched untouched`() {
        assertEquals("", mediaFormatQuery("cat.gif", "webp"))
        assertEquals("&format=webp", mediaFormatQuery("cat.png", "webp"))
        assertEquals("&format=png", mediaFormatQuery(null, "png"))
    }

    @Test
    fun `previews are scaled down to the screen and never enlarged`() {
        assertEquals(540 to 405, fitWithin(1080, 810, 540, 1000))
        assertEquals(400 to 300, fitWithin(400, 300, 1080, 1920))
        // Both limits bind: width first, then the already-narrowed height.
        assertEquals(133 to 100, fitWithin(4000, 3000, 200, 100))
    }
}
