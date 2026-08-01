package com.discord.rtcconnection.dave

internal class ByteReader(private val bytes: ByteArray) {
    private var offset = 0

    val remaining: Int
        get() = bytes.size - offset

    fun read(): Byte {
        if (offset == bytes.size) throw IndexOutOfBoundsException("ByteReader reached the end")
        return bytes[offset++]
    }

    fun read(count: Int): ByteArray {
        require(count >= 0) { "count must not be negative" }
        if (count > remaining) throw IndexOutOfBoundsException("ByteReader reached the end")
        return bytes.copyOfRange(offset, offset + count).also { offset += count }
    }

    fun readUint8(): Int = read().toInt() and 0xff

    fun readUint16(): Int = (readUint8() shl 8) or readUint8()

    fun collect(): ByteArray = read(remaining)
}
