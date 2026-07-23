package com.discord.api.sticker

enum class StickerFormatType(val apiValue: Int) {
    UNKNOWN(-1),
    PNG(1),
    APNG(2),
    LOTTIE(3),
    ;

    companion object {
        fun fromApiValue(value: Int) = entries.firstOrNull { it.apiValue == value } ?: UNKNOWN
    }
}
