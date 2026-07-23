package com.discord.api.sticker

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerContractsTest {
    private val gson = GsonBuilder()
        .registerTypeAdapter(StickerFormatType::class.java, StickerFormatTypeTypeAdapter())
        .registerTypeAdapter(StickerType::class.java, StickerTypeTypeAdapter())
        .create()

    @Test
    fun preservesWireValuesAndDerivedBehavior() {
        assertEquals(StickerFormatType.UNKNOWN, gson.fromJson("99", StickerFormatType::class.java))
        assertEquals("3", gson.toJson(StickerFormatType.LOTTIE, StickerFormatType::class.java))
        val png = Sticker(1, null, null, "name", "description", StickerFormatType.PNG, "tag", StickerType.GUILD)
        assertEquals(".png", png.getFileExtension())
        assertFalse(png.isAnimated())
        assertTrue(png.copy(formatType = StickerFormatType.APNG).isAnimated())
        assertEquals(StickerPartial(1, StickerFormatType.PNG, "name"), png.toStickerPartial())
    }
}
