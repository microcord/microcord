package com.discord.widgets.chat.input.emoji

import java.io.Serializable

sealed class EmojiPickerContextType : Serializable {
    object Chat : EmojiPickerContextType()
    object Global : EmojiPickerContextType()
    data class Guild(val guildId: Long) : EmojiPickerContextType()
}
