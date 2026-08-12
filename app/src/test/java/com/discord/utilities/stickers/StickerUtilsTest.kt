package com.discord.utilities.stickers

import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerPartial
import org.junit.Assert.assertEquals
import org.junit.Test

class StickerUtilsTest {
    /**
     * `size` is always passed explicitly here: its default is `DEFAULT_STICKER_SIZE_PX`, which reads
     * `Resources.getSystem()` and is therefore not resolvable in a plain JVM unit test.
     */
    @Test
    fun buildsReferenceCdnUrls() {
        val png = StickerPartial(42, StickerFormatType.PNG, "static")
        val lottie = StickerPartial(43, StickerFormatType.LOTTIE, "animated")

        assertEquals(
            "https://media.discordapp.net/stickers/42.png?passthrough=false&size=160",
            StickerUtils.getCDNAssetUrl(png, 160, passthrough = false),
        )
        assertEquals(
            "https://discord.com/stickers/43.json",
            StickerUtils.getCDNAssetUrl(lottie, 160),
        )
    }

    /**
     * The media proxy only returns the original APNG when `passthrough=true`; with `false` it
     * re-encodes to a single still frame, so an animated sticker downloaded that way can never
     * animate. The reference defaults the flag to `true` for exactly this reason.
     */
    @Test
    fun animatedStickerAssetsArePassedThroughByDefault() {
        val apng = StickerPartial(44, StickerFormatType.APNG, "animated")

        assertEquals(
            "https://media.discordapp.net/stickers/44.png?passthrough=true&size=160",
            StickerUtils.getCDNAssetUrl(apng, 160),
        )
        assertEquals(StickerFormatType.GIF, StickerFormatType.fromApiValue(4))
        assertEquals(
            "https://media.discordapp.net/stickers/46.gif?passthrough=true&size=160",
            StickerUtils.getCDNAssetUrl(StickerPartial(46, StickerFormatType.GIF, "gif"), 160),
        )
    }

    /** Requested sizes are quantised up to a width the media proxy will actually render. */
    @Test
    fun quantisesRequestedSize() {
        val apng = StickerPartial(45, StickerFormatType.APNG, "animated")

        assertEquals(
            "https://media.discordapp.net/stickers/45.png?passthrough=true&size=480",
            StickerUtils.getCDNAssetUrl(apng, 470),
        )
    }
}
