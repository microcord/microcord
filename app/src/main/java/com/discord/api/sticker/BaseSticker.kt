package com.discord.api.sticker

interface BaseSticker {
    val formatType: StickerFormatType
    fun getFileExtension(): String
    fun toStickerPartial(): StickerPartial
    fun getStickerId(): Long
}
