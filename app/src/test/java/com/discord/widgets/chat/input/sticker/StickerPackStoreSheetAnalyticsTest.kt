package com.discord.widgets.chat.input.sticker

import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickerPackStoreSheetAnalyticsTest {
    @Test
    fun `view type ordinals and analytics values match 126 21`() {
        assertEquals(
            listOf(
                "Sticker Popout View All",
                "Sticker Search View All",
                "Sticker Pack View All",
            ),
            StickerPackStoreSheetViewType.values().map { it.analyticsValue },
        )
    }

    @Test
    fun `fields and component order match the reference`() {
        val sticker = Sticker(
            id = 1L,
            packId = 2L,
            guildId = null,
            name = "Wave",
            description = "",
            formatType = StickerFormatType.PNG,
            tags = "wave",
            type = StickerType.STANDARD,
            available = true,
        )
        val analytics = StickerPackStoreSheetAnalytics(
            sticker,
            StickerPackStoreSheetViewType.STICKER_POPOUT_VIEW_ALL,
            null,
            "Sticker Popout",
        )

        assertEquals(sticker, analytics.component1())
        assertEquals(StickerPackStoreSheetViewType.STICKER_POPOUT_VIEW_ALL, analytics.component2())
        assertNull(analytics.component3())
        assertEquals("Sticker Popout", analytics.component4())
    }
}
