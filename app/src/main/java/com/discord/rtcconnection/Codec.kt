package com.discord.rtcconnection

/**
 * One codec this device can encode or decode, in the form the voice server negotiates over.
 *
 * [payloadType] and [rtxPayloadType] are RTP payload-type numbers; the video ones are assigned
 * client-side (`101, 102`, `103, 104`, …) in the order the engine ranked the codecs, so both ends
 * agree without an extra round trip. Audio is always `opus` at `120` with no retransmission type.
 *
 * Reference: `b.a.q.m0.a` (`Codec`, per its `toString`).
 */
data class Codec(
    val name: String,
    val priority: Int,
    val type: String,
    val payloadType: Int,
    val rtxPayloadType: Int?,
    val encode: Boolean? = null,
    val decode: Boolean? = null,
)
