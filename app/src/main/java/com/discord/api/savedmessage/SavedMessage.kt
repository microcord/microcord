package com.discord.api.savedmessage

import com.discord.api.message.Message
import com.discord.api.utcdatetime.UtcDateTime

data class SavedMessage(val message: Message?, val saveData: SaveData)

/** RN SavedMessagesTypes and Userdoccers' Save Data Structure; summaries are opaque strings. */
data class SaveData(
    val channelId: Long,
    val messageId: Long,
    val savedAt: UtcDateTime,
    val authorSummary: String,
    val channelSummary: String,
    val messageSummary: String,
    val guildId: Long?,
    val authorId: Long,
    val notes: String,
    val dueAt: UtcDateTime?,
) {
    override fun toString() =
        "SaveData(channelId=$channelId, messageId=$messageId, savedAt=$savedAt, " +
            "guildId=$guildId, authorId=$authorId, dueAt=$dueAt, summaries=<redacted>, notes=<redacted>)"
}

data class SavedMessagesResponse(val results: List<SavedMessage>)

data class SavedMessageDelete(val channelId: Long, val messageId: Long)
