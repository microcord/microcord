package com.discord.widgets.chat.list.adapter

import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetChatListAdapterItemStickerTest {

    @Test fun resolvesStandardBeforeGuild() {
        val calls = mutableListOf<String>()
        val standard = sticker(1L)

        assertEquals(
            standard,
            resolveSticker(
                1L,
                standardSticker = { calls += "standard"; standard },
                guildSticker = { calls += "guild"; sticker(2L) },
            ),
        )
        assertEquals(listOf("standard"), calls)
    }

    @Test fun fallsBackToGuildThenReportsMissing() {
        val guild = sticker(2L, StickerType.GUILD)

        assertEquals(guild, resolveSticker(2L, { null }, { guild }))
        assertNull(resolveSticker(3L, { null }, { null }))
    }

    private fun sticker(id: Long, type: StickerType = StickerType.STANDARD) = Sticker(
        id = id,
        packId = if (type == StickerType.STANDARD) 10L else null,
        guildId = if (type == StickerType.GUILD) 20L else null,
        name = "sticker-$id",
        description = "",
        formatType = StickerFormatType.PNG,
        tags = "",
        type = type,
    )
}
