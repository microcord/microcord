package com.discord.rtcconnection.dave

import okio.ByteString.Companion.decodeBase64

internal const val EPOCH_CODE_DIGITS = 30
internal const val PAIRWISE_CODE_DIGITS = 45

internal fun formatFingerprint(
    data: ByteArray,
    digits: Int = EPOCH_CODE_DIGITS,
    groupsPerLine: Int = 0,
): String {
    if (digits <= 0 || digits % GROUP_SIZE != 0 || data.size < digits) return ""
    val output = StringBuilder(digits + digits / GROUP_SIZE)

    repeat(digits / GROUP_SIZE) { group ->
        var value = 0L
        repeat(GROUP_SIZE) { index ->
            value = (value shl 8) or (data[group * GROUP_SIZE + index].toLong() and 0xff)
        }
        if (group > 0) output.append(if (groupsPerLine > 0 && group % groupsPerLine == 0) '\n' else ' ')
        output.append((value % GROUP_MODULUS).toString().padStart(GROUP_SIZE, '0'))
    }
    return output.toString()
}

internal fun formatFingerprintBase64(
    encoded: String,
    digits: Int = EPOCH_CODE_DIGITS,
    groupsPerLine: Int = 0,
): String = encoded.decodeBase64()?.toByteArray()?.let { formatFingerprint(it, digits, groupsPerLine) }.orEmpty()

private const val GROUP_SIZE = 5
private const val GROUP_MODULUS = 100_000L
