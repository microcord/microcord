package com.discord.utilities.websocket

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class ZlibStreamDecoder : GatewayStreamDecoder {
    private val inflater = Inflater()
    private val compressed = ByteArrayOutputStream()

    override fun decode(bytes: ByteArray): List<ByteArray> {
        compressed.write(bytes)
        val input = compressed.toByteArray()
        val messages = mutableListOf<ByteArray>()
        var frameStart = 0
        var index = 0
        while (index <= input.size - SUFFIX.size) {
            if (SUFFIX.indices.all { input[index + it] == SUFFIX[it] }) {
                messages += inflate(input.copyOfRange(frameStart, index + SUFFIX.size))
                frameStart = index + SUFFIX.size
                index = frameStart
            } else {
                index++
            }
        }
        if (frameStart > 0) {
            compressed.reset()
            compressed.write(input, frameStart, input.size - frameStart)
        }
        return messages
    }

    override fun reset() {
        inflater.reset()
        compressed.reset()
    }

    override fun close() = inflater.end()

    private fun inflate(frame: ByteArray): ByteArray {
        inflater.setInput(frame)
        val output = ByteArrayOutputStream(frame.size * 2)
        val buffer = ByteArray(4096)
        try {
            while (!inflater.needsInput()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                output.write(buffer, 0, count)
            }
        } catch (error: DataFormatException) {
            reset()
            throw error
        }
        return output.toByteArray()
    }

    private companion object {
        val SUFFIX = byteArrayOf(0, 0, 0xff.toByte(), 0xff.toByte())
    }
}
