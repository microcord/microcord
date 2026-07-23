package com.discord.utilities.stickers

import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerPartial
import org.junit.Assert.assertEquals
import org.junit.Test

class StickerUtilsTest {
    @Test
    fun buildsReferenceCdnUrlsAndAppliesReducedMotion() {
        val png = StickerPartial(42, StickerFormatType.PNG, "static")
        val lottie = StickerPartial(43, StickerFormatType.LOTTIE, "animated")

        assertEquals(
            "https://media.discordapp.net/stickers/42.png?passthrough=false&size=160",
            StickerUtils.getCDNAssetUrl(png, 160),
        )
        assertEquals(
            "https://discord.com/stickers/43.json",
            StickerUtils.getCDNAssetUrl(lottie),
        )
        assertEquals(
            StickerUtils.ANIMATE_ON_INTERACTION,
            StickerUtils.effectiveAnimationSetting(StickerUtils.ALWAYS_ANIMATE, true),
        )
        assertEquals(
            StickerUtils.NEVER_ANIMATE,
            StickerUtils.effectiveAnimationSetting(StickerUtils.NEVER_ANIMATE, true),
        )
    }
}
