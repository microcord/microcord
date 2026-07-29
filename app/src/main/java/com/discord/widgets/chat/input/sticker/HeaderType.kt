package com.discord.widgets.chat.input.sticker

import com.discord.models.guild.Guild
import com.discord.models.sticker.dto.ModelStickerPack

sealed class HeaderType {
    abstract fun getId(): String

    data class GuildItem(val guild: Guild) : HeaderType() {
        override fun getId(): String = guild.id.toString()
    }

    data class PackItem(val pack: ModelStickerPack) : HeaderType() {
        override fun getId(): String = "owned-header:${pack.id}"
    }

    object Recent : HeaderType() {
        override fun getId(): String = "recents"
    }
}
