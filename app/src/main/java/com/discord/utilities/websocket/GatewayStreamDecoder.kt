package com.discord.utilities.websocket

interface GatewayStreamDecoder : AutoCloseable {
    fun decode(bytes: ByteArray): List<ByteArray>
    fun reset()
    override fun close() = Unit
}

enum class GatewayCompression(val queryValue: String) {
    ZLIB("zlib-stream"),
    ZSTD("zstd-stream"),
    ;

    fun createDecoder(): GatewayStreamDecoder = when (this) {
        ZLIB -> ZlibStreamDecoder()
        ZSTD -> ZstdStreamDecoder()
    }
}
