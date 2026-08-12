package com.discord.api.sticker

import java.io.Serializable

data class Sticker(
    val id: Long,
    val packId: Long?,
    val guildId: Long?,
    val name: String,
    val description: String,
    override val formatType: StickerFormatType,
    val tags: String,
    val type: StickerType,
    val available: Boolean? = null,
) : Serializable, BaseSticker {
    @Transient var version: Long = 0

    override fun getFileExtension() = formatType.fileExtension()
    override fun toStickerPartial() = StickerPartial(id, formatType, name)
    override fun getStickerId() = id
    fun isAnimated() = formatType in setOf(StickerFormatType.APNG, StickerFormatType.LOTTIE, StickerFormatType.GIF)
}

internal fun StickerFormatType.fileExtension() = when (this) {
    StickerFormatType.UNKNOWN -> ""
    StickerFormatType.PNG, StickerFormatType.APNG -> ".png"
    StickerFormatType.LOTTIE -> ".json"
    StickerFormatType.GIF -> ".gif"
}
