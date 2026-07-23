package com.discord.api.sticker

enum class StickerType(val apiValue: Int) {
    UNKNOWN(-1),
    STANDARD(1),
    GUILD(2),
    ;

    companion object {
        fun fromApiValue(value: Int) = entries.firstOrNull { it.apiValue == value } ?: UNKNOWN
    }
}
