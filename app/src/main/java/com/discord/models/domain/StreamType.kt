package com.discord.models.domain

/** The two shapes a stream key can take; see [ModelApplicationStream.decodeStreamKey]. */
enum class StreamType(val serializedRepresentation: String) {
    GUILD("guild"),
    CALL("call");

    companion object {
        private val VALUES = entries.toTypedArray()

        fun fromString(string: String): StreamType =
            VALUES.firstOrNull { it.serializedRepresentation == string }
                ?: throw IllegalArgumentException("unable to parse stream type: $string")
    }
}
