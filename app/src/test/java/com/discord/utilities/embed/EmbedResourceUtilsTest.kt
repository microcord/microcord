package com.discord.utilities.embed

import com.discord.api.message.embed.EmbedAuthor
import com.discord.api.message.embed.EmbedImage
import com.discord.api.message.embed.EmbedProvider
import com.discord.api.message.embed.EmbedThumbnail
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedVideo
import com.discord.api.message.embed.MessageEmbed
import com.discord.embed.RenderableEmbedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedResourceUtilsTest {

    // The reference declares these as member extensions of the object, so callers scope them with
    // `with(EmbedResourceUtils)`. These wrappers keep that out of every assertion.
    private fun previewOf(subject: MessageEmbed): RenderableEmbedMedia? =
        with(EmbedResourceUtils) { subject.getPreviewImage() }

    private fun playable(subject: MessageEmbed): Boolean =
        with(EmbedResourceUtils) { subject.isPlayable() }

    private fun inline(subject: MessageEmbed): Boolean =
        with(EmbedResourceUtils) { subject.isInlineEmbed() }

    private fun simple(subject: MessageEmbed): Boolean =
        with(EmbedResourceUtils) { subject.isSimpleEmbed() }

    private fun externalOpenUrl(subject: MessageEmbed): String? =
        with(EmbedResourceUtils) { subject.getExternalOpenUrl() }

    private fun embed(
        type: EmbedType?,
        image: EmbedImage? = null,
        thumbnail: EmbedThumbnail? = null,
        video: EmbedVideo? = null,
        provider: EmbedProvider? = null,
        author: EmbedAuthor? = null,
        title: String? = null,
    ) = MessageEmbed(
        title = title,
        type = type,
        description = null,
        url = null,
        timestamp = null,
        color = null,
        footer = null,
        image = image,
        thumbnail = thumbnail,
        video = video,
        provider = provider,
        author = author,
        fields = null,
    )

    @Test
    fun `a video previews from its thumbnail and falls back to the video`() {
        val thumb = EmbedThumbnail("t", "pt", 1, 2)
        val video = EmbedVideo("v", "pv", 3, 4)

        assertEquals("pt", previewOf(embed(EmbedType.VIDEO, thumbnail = thumb, video = video))?.url)
        assertEquals("pv", previewOf(embed(EmbedType.VIDEO, video = video))?.url)
    }

    @Test
    fun `link and html embeds never preview, even when they carry an image`() {
        val image = EmbedImage("i", "pi", 1, 1)
        assertNull(previewOf(embed(EmbedType.LINK, image = image)))
        assertNull(previewOf(embed(EmbedType.HTML, image = image)))
    }

    @Test
    fun `an article previews from its thumbnail, not its image`() {
        val subject = embed(
            EmbedType.ARTICLE,
            image = EmbedImage("i", "pi", 1, 1),
            thumbnail = EmbedThumbnail("t", "pt", 1, 1),
        )
        assertEquals("pt", previewOf(subject)?.url)
    }

    @Test
    fun `the proxy url wins over the origin url`() {
        val subject = embed(EmbedType.RICH, image = EmbedImage("origin", "proxy", 1, 1))
        assertEquals("proxy", previewOf(subject)?.url)
    }

    @Test
    fun `the origin url is used when there is no proxy`() {
        val subject = embed(EmbedType.RICH, image = EmbedImage("origin", null, 1, 1))
        assertEquals("origin", previewOf(subject)?.url)
    }

    @Test
    fun `video and gifv types are playable but a plain image is not`() {
        assertTrue(playable(embed(EmbedType.VIDEO)))
        assertTrue(playable(embed(EmbedType.GIFV)))
        assertFalse(playable(embed(EmbedType.IMAGE)))
    }

    @Test
    fun `a rich video uses its poster and keeps the card while ordinary rich embeds stay images`() {
        val thumbnail = EmbedThumbnail("https://example.com/poster.jpg", "https://proxy.example.com/poster.jpg", 800, 1152)
        val video = EmbedVideo("https://example.com/stream/opaque-id", "https://proxy.example.com/stream/opaque-id", 800, 1152)
        val image = EmbedImage("https://example.com/image.jpg", "https://proxy.example.com/image.jpg", 320, 240)
        val subject = embed(EmbedType.RICH, thumbnail = thumbnail, video = video, image = image)

        assertTrue(playable(subject))
        assertFalse(inline(subject))
        assertFalse(simple(subject))
        assertNull(externalOpenUrl(subject))
        assertEquals(RenderableEmbedMedia(thumbnail.proxyUrl, 1152, 800), previewOf(subject))
        assertEquals(RenderableEmbedMedia(video.proxyUrl, 1152, 800), previewOf(subject.copy(thumbnail = null)))
        assertFalse(playable(subject.copy(video = null)))
        assertEquals(image.proxyUrl, previewOf(subject.copy(video = null))?.url)
        assertNull(previewOf(subject.copy(video = null, image = null)))
        assertFalse(playable(subject.copy(video = video.copy(url = null, proxyUrl = null))))
        assertFalse(playable(subject.copy(video = video.copy(url = "", proxyUrl = null))))
    }

    @Test
    fun `an image embed with an author or a title needs its card`() {
        // An `IMAGE` embed previews from its *thumbnail*, exactly like `ARTICLE` and `TWEET` —
        // giving it only an `image` leaves it with no preview and so never inline. Verified
        // against the reference `getPreviewImage`, whose ordinal-0/1/3 branch reads `thumbnail`.
        val thumbnail = EmbedThumbnail("t", "pt", 1, 1)
        assertTrue(inline(embed(EmbedType.IMAGE, thumbnail = thumbnail)))
        assertFalse(
            inline(embed(EmbedType.IMAGE, thumbnail = thumbnail, title = "Titled")),
        )
        assertFalse(
            inline(
                embed(
                    EmbedType.IMAGE,
                    thumbnail = thumbnail,
                    author = EmbedAuthor("a", null, null, null),
                ),
            ),
        )
    }

    @Test
    fun `a gifv is inline regardless of its author and title`() {
        val subject = embed(
            EmbedType.GIFV,
            video = EmbedVideo("v", "pv", 1, 1),
            title = "Titled",
            author = EmbedAuthor("a", null, null, null),
        )
        assertTrue(inline(subject))
    }

    @Test
    fun `an embed with no media at all is not inline`() {
        assertFalse(inline(embed(EmbedType.IMAGE)))
    }

    @Test
    fun `a video is inline but never simple`() {
        val subject = embed(EmbedType.VIDEO, video = EmbedVideo("v", "pv", 1, 1))
        assertTrue(inline(subject))
        assertFalse(simple(subject))
    }

    @Test
    fun `external open url comes from the video, or the provider for a file`() {
        assertEquals(
            "https://v",
            externalOpenUrl(embed(EmbedType.VIDEO, video = EmbedVideo("https://v", null, 1, 1))),
        )
        assertEquals(
            "https://p",
            externalOpenUrl(embed(EmbedType.FILE, provider = EmbedProvider("p", "https://p"))),
        )
        assertNull(externalOpenUrl(embed(EmbedType.IMAGE)))
    }

    @Test
    fun `gifvs always animate, images only when the url is a gif`() {
        assertTrue(EmbedResourceUtils.isAnimated(EmbedType.GIFV, null))
        assertTrue(EmbedResourceUtils.isAnimated(EmbedType.IMAGE, "https://cdn/A.GIF"))
        assertFalse(EmbedResourceUtils.isAnimated(EmbedType.IMAGE, "https://cdn/a.png"))
        assertFalse(EmbedResourceUtils.isAnimated(EmbedType.IMAGE, null))
        assertFalse(EmbedResourceUtils.isAnimated(null, "https://cdn/a.gif"))
    }

    @Test
    fun `a still variant is preferred, with the unsized original as the fallback`() {
        assertEquals(
            listOf(
                "https://cdn/a.png?width=10&height=20&format=webp",
                "https://cdn/a.png?width=10&height=20",
            ),
            EmbedResourceUtils.getPreviewUrls("https://cdn/a.png", 10, 20),
        )
    }

    @Test
    fun `an animating gif is requested as-is, with no still fallback`() {
        assertEquals(
            listOf("https://cdn/a.gif?width=10&height=20"),
            EmbedResourceUtils.getPreviewUrls("https://cdn/a.gif", 10, 20),
        )
        assertEquals(
            listOf("res:///1234?width=10&height=20"),
            EmbedResourceUtils.getPreviewUrls("res:///1234", 10, 20),
        )
    }

    @Test
    fun `sized previews preserve existing origin and signed query parameters`() {
        assertEquals(listOf(
            "https://example.com/image.jpg?name=orig&width=10&height=20&format=webp",
            "https://example.com/image.jpg?name=orig&width=10&height=20",
        ), EmbedResourceUtils.getPreviewUrls("https://example.com/image.jpg?name=orig", 10, 20))
        assertEquals(listOf("https://example.com/image.gif?ex=1&is=2&width=10&height=20"),
            EmbedResourceUtils.getPreviewUrls("https://example.com/image.gif?ex=1&is=2", 10, 20))
    }

    @Test
    fun `shouldAnimate false forces the still variant even for a gif`() {
        assertEquals(
            listOf(
                "https://cdn/a.gif?width=10&height=20&format=webp",
                "https://cdn/a.gif?width=10&height=20",
            ),
            EmbedResourceUtils.getPreviewUrls("https://cdn/a.gif", 10, 20, shouldAnimate = false),
        )
    }
}
