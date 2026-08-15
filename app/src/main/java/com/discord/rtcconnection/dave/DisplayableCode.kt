package com.discord.rtcconnection.dave

/**
 * Renders DAVE fingerprint bytes as the digits users read out to each other.
 *
 * Every client has to derive the same digits from the same bytes or comparing them proves nothing,
 * so this follows libdave exactly: [groupSize] bytes per group, big-endian, taken modulo
 * `10^groupSize` and zero-padded.
 */
fun generateDisplayableCode(data: ByteArray, desiredLength: Int, groupSize: Int): String? {
    if (data.size < desiredLength || desiredLength % groupSize != 0 || groupSize > MAX_GROUP_SIZE) return null

    val modulus = POWERS_OF_TEN[groupSize]
    return buildString {
        for (offset in 0 until desiredLength step groupSize) {
            var value = 0L
            for (index in 0 until groupSize) {
                value = (value shl 8) or (data[offset + index].toLong() and 0xff)
            }
            append((value % modulus).toString().padStart(groupSize, '0'))
        }
    }
}

private const val MAX_GROUP_SIZE = 8

private val POWERS_OF_TEN = LongArray(MAX_GROUP_SIZE + 1) { exponent ->
    var value = 1L
    repeat(exponent) { value *= 10 }
    value
}
