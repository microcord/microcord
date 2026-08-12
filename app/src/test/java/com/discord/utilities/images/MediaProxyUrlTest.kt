package com.discord.utilities.images

import com.discord.api.message.attachment.MessageAttachment
import com.discord.utilities.icon.IconUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProxyUrlTest {

    private val webp = IconUtils.staticImageExtension

    private fun attachment(filename: String, flags: Int? = null) = MessageAttachment(
        url = "https://cdn.discordapp.com/attachments/1/2/$filename",
        size = 1,
        id = 2,
        proxyUrl = "https://media.discordapp.net/attachments/1/2/$filename?ex=65d903de&hm=abc",
        filename = filename,
        width = 100,
        height = 100,
        flags = flags,
    )

    @Test
    fun `avif is transcoded because android cannot decode it before api 31`() {
        val a = attachment("pic.avif")
        assertEquals(
            "https://media.discordapp.net/attachments/1/2/pic.avif?ex=65d903de&hm=abc&format=$webp",
            MediaProxyUrl.forAttachment(a, a.proxyUrl),
        )
    }

    @Test
    fun `an animated webp asks for every frame`() {
        val a = attachment("pic.webp", flags = MessageAttachment.FLAG_IS_ANIMATED)
        assertEquals(
            "https://media.discordapp.net/attachments/1/2/pic.webp?ex=65d903de&hm=abc&animated=true",
            MediaProxyUrl.forAttachment(a, a.proxyUrl),
        )
    }

    @Test
    fun `a still webp is left alone so the proxy returns one frame`() {
        val a = attachment("pic.webp")
        assertEquals(a.proxyUrl, MediaProxyUrl.forAttachment(a, a.proxyUrl))
    }

    @Test
    fun `an animated avif is both transcoded and unfrozen`() {
        val a = attachment("pic.avif", flags = MessageAttachment.FLAG_IS_ANIMATED)
        assertEquals(
            "https://media.discordapp.net/attachments/1/2/pic.avif?ex=65d903de&hm=abc" +
                "&format=$webp&animated=true",
            MediaProxyUrl.forAttachment(a, a.proxyUrl),
        )
    }

    @Test
    fun `a gif is never rewritten, since transcoding would flatten it`() {
        val a = attachment("pic.gif", flags = MessageAttachment.FLAG_IS_ANIMATED)
        assertEquals(a.proxyUrl, MediaProxyUrl.forAttachment(a, a.proxyUrl))
    }

    @Test
    fun `the signature survives every rewrite`() {
        val a = attachment("pic.avif", flags = MessageAttachment.FLAG_IS_ANIMATED)
        val result = MediaProxyUrl.forAttachment(a, a.proxyUrl)
        assertEquals(true, result.contains("ex=65d903de"))
        assertEquals(true, result.contains("hm=abc"))
    }

    @Test
    fun `rewriting twice does not duplicate a parameter`() {
        val a = attachment("pic.avif")
        val once = MediaProxyUrl.forAttachment(a, a.proxyUrl)
        assertEquals(once, MediaProxyUrl.forAttachment(a, once))
    }

    @Test
    fun `stripping the signature leaves the rest of the query`() {
        assertEquals(
            "https://media.discordapp.net/a.png?width=100",
            MediaProxyUrl.withoutSignature("https://media.discordapp.net/a.png?ex=1&width=100&is=2&hm=3"),
        )
        assertEquals(
            "https://media.discordapp.net/a.png",
            MediaProxyUrl.withoutSignature("https://media.discordapp.net/a.png?ex=1&is=2&hm=3"),
        )
        assertEquals(
            "https://media.discordapp.net/a.png",
            MediaProxyUrl.withoutSignature("https://media.discordapp.net/a.png"),
        )
    }

    @Test
    fun `a query string does not make an extension unrecognisable`() {
        assertEquals(true, MediaProxyUrl.isAvif("https://x/a.avif?ex=1"))
        assertEquals(false, MediaProxyUrl.isAvif("https://x/a.png?name=b.avif"))
    }
}
