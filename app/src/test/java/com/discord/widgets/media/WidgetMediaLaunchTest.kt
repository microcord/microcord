package com.discord.widgets.media

import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.embed.EmbedThumbnail
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedVideo
import com.discord.api.message.embed.MessageEmbed
import com.discord.player.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the decisions [WidgetMedia] makes before it builds its launch intent.
 *
 * These are the ones that determine whether a tapped image opens as a still, plays as a video, or
 * silently opens nothing at all.
 */
class WidgetMediaLaunchTest {

    @Test
    fun `profile image opens as the original fullscreen still`() {
        val args = launchArgs("https://cdn.discordapp.com/avatars/1/hash.webp?size=2048", "user")

        assertEquals(args.uri, args.previewImageUri)
        assertEquals("user", args.title)
        assertNull(args.progressiveMediaUri)
        assertNull(args.mediaType)
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
        assertEquals("https://media.discordapp.net/proxied", args.previewImageUri)
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
