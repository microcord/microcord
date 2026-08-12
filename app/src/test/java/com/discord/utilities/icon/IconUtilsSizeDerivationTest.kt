package com.discord.utilities.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [IconUtils.withRequestedSize] takes device pixels, so no density is applied on top. */
class IconUtilsSizeDerivationTest {

    @Test
    fun `the request is quantised to the pixels the view decodes into`() {
        assertEquals(
            "https://cdn.discordapp.com/icons/1/hash.webp?size=128",
            IconUtils.withRequestedSize("https://cdn.discordapp.com/icons/1/hash.webp", 101),
        )
    }

    @Test
    fun `an existing size is replaced rather than appended`() {
        assertEquals(
            "https://cdn.discordapp.com/icons/1/hash.webp?size=48",
            IconUtils.withRequestedSize("https://cdn.discordapp.com/icons/1/hash.webp?size=4096", 48),
        )
    }

    @Test
    fun `other parameters survive the rewrite`() {
        assertEquals(
            "https://cdn.discordapp.com/icons/1/a_h.webp?animated=true&size=64",
            IconUtils.withRequestedSize("https://cdn.discordapp.com/icons/1/a_h.webp?animated=true", 64),
        )
    }

    @Test
    fun `a signed link is left exactly as issued`() {
        val signed = "https://media.discordapp.net/attachments/1/2/a.png?ex=65d903de&is=65c68ede&hm=abc"
        assertEquals(signed, IconUtils.withRequestedSize(signed, 64))
    }

    @Test
    fun `non-network sources are untouched`() {
        assertEquals(
            "asset://asset/images/default_avatar_1.png",
            IconUtils.withRequestedSize("asset://asset/images/default_avatar_1.png", 64),
        )
        assertEquals("data:image/png;base64,AAAA", IconUtils.withRequestedSize("data:image/png;base64,AAAA", 64))
    }

    @Test
    fun `an unconstrained view leaves the url alone`() {
        val url = "https://cdn.discordapp.com/icons/1/hash.webp"
        assertEquals(url, IconUtils.withRequestedSize(url, 0))
        assertNull(IconUtils.withRequestedSize(null, 64))
    }
}
