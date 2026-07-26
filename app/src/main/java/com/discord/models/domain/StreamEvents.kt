package com.discord.models.domain

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.util.Locale

/**
 * Payload of `STREAM_CREATE` / `STREAM_UPDATE`.
 *
 * [streamKey] is the encoded `ModelApplicationStream` key; decode it with
 * [ModelApplicationStream.decodeStreamKey] to learn the owning guild/channel/user.
 */
data class StreamCreateOrUpdate(
    val streamKey: String,
    val rtcServerId: String? = null,
    val paused: Boolean = false,
    val viewerIds: List<Long>? = null,
) {
    /** Viewers of the stream; empty when the update omits the list rather than sending `[]`. */
    val viewers: List<Long> get() = viewerIds.orEmpty()
}

/** Payload of `STREAM_DELETE`. */
data class StreamDelete(
    val streamKey: String,
    val reason: Reason? = null,
    val unavailable: Boolean = false,
) {
    /** [reason] is absent for a normal end-of-stream, which the client treats as [Reason.UNKNOWN]. */
    val deleteReason: Reason get() = reason ?: Reason.UNKNOWN

    @JsonAdapter(ReasonTypeAdapter::class)
    enum class Reason(val apiValue: String) {
        USER_REQUESTED("user_requested"),
        STREAM_FULL("stream_full"),
        UNAUTHORIZED("unauthorized"),
        UNKNOWN("unknown"),
    }

    class ReasonTypeAdapter : TypeAdapter<Reason>() {
        override fun read(reader: JsonReader): Reason {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return Reason.UNKNOWN
            }
            val value = reader.nextString().lowercase(Locale.ROOT)
            return Reason.entries.firstOrNull { it.apiValue == value } ?: Reason.UNKNOWN
        }

        override fun write(writer: JsonWriter, value: Reason?) {
            if (value == null) writer.nullValue() else writer.value(value.apiValue)
        }
    }
}

/** Payload of `STREAM_SERVER_UPDATE`: the RTC endpoint for a stream the client is watching. */
data class StreamServerUpdate(
    val streamKey: String,
    val endpoint: String?,
    val token: String?,
) {
    override fun toString(): String =
        "StreamServerUpdate(streamKey=$streamKey, endpoint=$endpoint, token=<redacted>)"
}
