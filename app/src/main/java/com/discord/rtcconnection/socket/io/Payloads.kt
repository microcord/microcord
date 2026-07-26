package com.discord.rtcconnection.socket.io

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * Wire payloads of the voice control WebSocket.
 *
 * Field order, nullability and JSON names are taken from the reference
 * `com.discord.rtcconnection.socket.io.Payloads`; the `@SerializedName` annotations are the only
 * thing that makes the snake_case wire form work, since the RTC socket's Gson instance is a plain
 * `Gson()` with no field-naming policy (unlike the main gateway's).
 *
 * Visibility follows the reference: the object and the four types that leak into public model
 * classes ([ResolutionType], [Protocol.CodecInfo], [Protocol.ProtocolInfo], [Stream.MaxResolution])
 * are public; every frame type is `internal`, because nothing outside the transport should be
 * building or reading raw voice frames.
 */
object Payloads {

    /** Opcode 13 — a user left the voice session. */
    internal data class ClientDisconnect(
        @SerializedName("user_id") val userId: Long,
    )

    /**
     * Opcode 4 — the server accepted our transport and is handing over the session secrets.
     *
     * [secretKey] is the symmetric key for the RTP encryption mode named by [mode]; it never leaves
     * this class, and [toString] is overridden so a payload trace cannot leak it.
     */
    internal data class Description(
        @SerializedName("secret_key") val secretKey: List<Int>,
        @SerializedName("media_session_id") val mediaSessionId: String?,
        @SerializedName("audio_codec") val audioCodec: String,
        @SerializedName("video_codec") val videoCodec: String,
        val mode: String,
    ) {
        override fun toString(): String =
            "Description(secretKey=<redacted>, mediaSessionId=$mediaSessionId, " +
                "audioCodec=$audioCodec, videoCodec=$videoCodec, mode=$mode)"
    }

    /** Opcode 8 — first server frame; carries the heartbeat cadence and the protocol version. */
    internal data class Hello(
        @SerializedName("heartbeat_interval") val heartbeatIntervalMs: Long,
        @SerializedName("v") val serverVersion: Int,
    )

    /**
     * Opcode 0 — authenticate a fresh voice session.
     *
     * [token] is the one-shot voice token from `VOICE_SERVER_UPDATE`; redacted in [toString].
     */
    internal data class Identify(
        @SerializedName("server_id") val serverId: String,
        @SerializedName("user_id") val userId: Long,
        @SerializedName("session_id") val sessionId: String,
        val token: String,
        val video: Boolean,
        val streams: List<Stream>,
    ) {
        override fun toString(): String =
            "Identify(serverId=$serverId, userId=$userId, sessionId=<redacted>, token=<redacted>, " +
                "video=$video, streams=$streams)"
    }

    /** Envelope of every server frame. [data] stays unparsed until the opcode selects a type. */
    internal data class Incoming(
        @SerializedName("op") val opcode: Int,
        @SerializedName("d") val data: JsonElement,
    )

    /** Envelope of every client frame. */
    internal data class Outgoing(
        @SerializedName("op") val opcode: Int,
        @SerializedName("d") val data: Any,
    )

    /** Opcode 1 — tell the server which transport the media engine opened. */
    internal data class Protocol(
        val protocol: String,
        val data: ProtocolInfo,
        val codecs: List<CodecInfo>?,
    ) {
        data class CodecInfo(
            val name: String,
            val priority: Int,
            val type: String,
            @SerializedName("payload_type") val payloadType: Int,
            @SerializedName("rtx_payload_type") val rtxPayloadType: Int?,
        )

        data class ProtocolInfo(
            val address: String,
            val port: Int,
            val mode: String,
        )
    }

    /** Opcode 2 — the dedicated media server we should send RTP to. */
    internal data class Ready(
        val ssrc: Int,
        val port: Int,
        val ip: String,
        val streams: List<Stream>,
    )

    /** How a video stream's resolution is negotiated. */
    enum class ResolutionType {
        @SerializedName("source") Source,
        @SerializedName("fixed") Fixed,
    }

    /** Opcode 7 — resume an interrupted session instead of paying for a full handshake. */
    internal data class Resume(
        val token: String,
        @SerializedName("session_id") val sessionId: String,
        @SerializedName("server_id") val serverId: String,
    ) {
        override fun toString(): String =
            "Resume(token=<redacted>, sessionId=<redacted>, serverId=$serverId)"
    }

    /** Opcode 14 — the media session was re-keyed (for example when a stream starts). */
    internal data class SessionUpdate(
        @SerializedName("media_session_id") val mediaSessionId: String?,
    )

    /**
     * Opcode 5 — voice activity, in both directions.
     *
     * Outbound frames carry [ssrc] and [speaking]; inbound frames additionally carry [userId], which
     * is how the client learns the ssrc→user mapping for everyone else in the channel.
     */
    internal data class Speaking(
        val ssrc: Int,
        val speaking: Int? = null,
        val delay: Int? = null,
        @SerializedName("user_id") val userId: Long? = null,
    )

    /** One simulcast layer, used in `IDENTIFY`, `READY`, `VIDEO` and the per-user video updates. */
    internal data class Stream(
        val type: String? = null,
        val rid: String? = null,
        @SerializedName("max_framerate") val maxFrameRate: Int? = null,
        val quality: Int? = null,
        val ssrc: Int? = null,
        @SerializedName("rtx_ssrc") val rtxSsrc: Int? = null,
        @SerializedName("max_resolution") val maxResolution: MaxResolution? = null,
        val active: Boolean? = null,
        @SerializedName("max_bitrate") val maxBitrate: Int? = null,
    ) {
        data class MaxResolution(
            val type: ResolutionType,
            val width: Int,
            val height: Int,
        )
    }

    /** Opcode 12 — a user's video ssrc set, in both directions. */
    internal data class Video(
        @SerializedName("audio_ssrc") val audioSsrc: Int,
        @SerializedName("video_ssrc") val videoSsrc: Int,
        @SerializedName("rtx_ssrc") val rtxSsrc: Int,
        @SerializedName("user_id") val userId: Long? = null,
        val streams: List<Stream>?,
    )
}
