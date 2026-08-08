package com.discord.utilities.websocket

import com.github.luben.zstd.ZstdDecompressCtx
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Discord zstd-stream: one WebSocket message per JSON payload, one context per connection. */
class ZstdStreamDecoder : GatewayStreamDecoder {
    private val context = ZstdDecompressCtx()
    private var source = ByteBuffer.allocateDirect(INITIAL_INPUT_SIZE)
    private val target = ByteBuffer.allocateDirect(OUTPUT_CHUNK_SIZE)
    private val outputChunk = ByteArray(OUTPUT_CHUNK_SIZE)

    override fun decode(bytes: ByteArray): List<String> {
        require(bytes.isNotEmpty()) { "Empty zstd Gateway message" }
        require(bytes.size <= MAX_COMPRESSED_MESSAGE_SIZE) { "Compressed Gateway message is too large" }
        ensureInputCapacity(bytes.size)
        source.clear()
        source.put(bytes)
        source.flip()

        val output = ByteArrayOutputStream((bytes.size * 3).coerceIn(INITIAL_INPUT_SIZE, OUTPUT_CHUNK_SIZE))
        var outputWasFull: Boolean
        do {
            target.clear()
            context.decompressDirectByteBufferStream(target, source)
            outputWasFull = !target.hasRemaining()
            target.flip()
            val size = target.remaining()
            target.get(outputChunk, 0, size)
            output.write(outputChunk, 0, size)
            require(output.size() <= MAX_DECOMPRESSED_MESSAGE_SIZE) { "Decompressed Gateway message is too large" }
        } while (source.hasRemaining() || outputWasFull)

        require(output.size() > 0) { "Zstd Gateway message produced no output" }
        return listOf(output.toString(Charsets.UTF_8.name()))
    }

    override fun reset() = context.reset()

    override fun close() = context.close()

    private fun ensureInputCapacity(size: Int) {
        if (source.capacity() >= size) return
        var capacity = source.capacity()
        while (capacity < size) capacity = capacity shl 1
        source = ByteBuffer.allocateDirect(capacity)
    }

    private companion object {
        const val INITIAL_INPUT_SIZE = 8 * 1024
        const val OUTPUT_CHUNK_SIZE = 64 * 1024
        const val MAX_COMPRESSED_MESSAGE_SIZE = 16 * 1024 * 1024
        const val MAX_DECOMPRESSED_MESSAGE_SIZE = 64 * 1024 * 1024
    }
}
