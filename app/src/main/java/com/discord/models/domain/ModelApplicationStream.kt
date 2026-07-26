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
        override val type = StreamType.CALL.serializedRepresentation
        override fun encodeStreamKey() = "$type:$channelId:$ownerId"
    }

    data class GuildStream(
        val guildId: Long,
        override val channelId: Long,
        override val ownerId: Long,
    ) : ModelApplicationStream(channelId, ownerId) {
        override val type = StreamType.GUILD.serializedRepresentation
        override fun encodeStreamKey() = "$type:$guildId:$channelId:$ownerId"
    }

    companion object {
        fun decodeStreamKey(streamKey: String): ModelApplicationStream {
            val parts = streamKey.split(":")
            return when (StreamType.fromString(parts[0])) {
                StreamType.CALL -> CallStream(parts[1].toLong(), parts[2].toLong())
                StreamType.GUILD -> GuildStream(parts[1].toLong(), parts[2].toLong(), parts[3].toLong())
            }
        }
    }
}
