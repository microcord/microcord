package com.discord.models.domain

import java.io.Serializable

sealed class ModelApplicationStream(
    open val channelId: Long,
    open val ownerId: Long,
) : Serializable {
    abstract val type: String
    val encodedStreamKey by lazy(::encodeStreamKey)

    protected abstract fun encodeStreamKey(): String

    data class CallStream(
        override val channelId: Long,
        override val ownerId: Long,
    ) : ModelApplicationStream(channelId, ownerId) {
        override val type = "call"
        override fun encodeStreamKey() = "$type:$channelId:$ownerId"
    }

    data class GuildStream(
        val guildId: Long,
        override val channelId: Long,
        override val ownerId: Long,
    ) : ModelApplicationStream(channelId, ownerId) {
        override val type = "guild"
        override fun encodeStreamKey() = "$type:$guildId:$channelId:$ownerId"
    }

    companion object {
        fun decodeStreamKey(streamKey: String): ModelApplicationStream {
            val parts = streamKey.split(':')
            return when (parts.firstOrNull()) {
                "call" -> CallStream(parts[1].toLong(), parts[2].toLong())
                "guild" -> GuildStream(parts[1].toLong(), parts[2].toLong(), parts[3].toLong())
                else -> throw IllegalArgumentException("Unknown stream type")
            }
        }
    }
}
