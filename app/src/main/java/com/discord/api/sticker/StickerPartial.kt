package com.discord.api.sticker

import java.io.Serializable

data class StickerPartial(
    val id: Long,
    override val formatType: StickerFormatType,
    val name: String,
) : Serializable, BaseSticker {
    override fun getFileExtension() = formatType.fileExtension()
    override fun toStickerPartial() = this
    override fun getStickerId() = id
}
