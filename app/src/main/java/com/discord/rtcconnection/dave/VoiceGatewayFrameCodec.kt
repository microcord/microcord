package com.discord.rtcconnection.dave

import com.discord.rtcconnection.socket.io.Payloads
import com.google.gson.Gson
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal object VoiceGatewayFrameCodec {
    data class BinaryFrame(
        val sequence: Int?,
        val opcode: Int,
        val payload: ByteArray,
    )

    fun decodeText(gson: Gson, text: String): Payloads.Incoming =
        gson.fromJson(text, Payloads.Incoming::class.java)

    fun decodeBinary(bytes: ByteString, version: Int): BinaryFrame {
        val reader = ByteReader(bytes.toByteArray())
        val sequence = if (version >= 8) reader.readUint16() else null
        return BinaryFrame(sequence, reader.readUint8(), reader.collect())
    }

    fun encodeBinary(opcode: Int, payload: ByteArray): ByteString {
        require(opcode in 0..255) { "opcode must fit in one byte" }
        return (byteArrayOf(opcode.toByte()) + payload).toByteString()
    }
}
