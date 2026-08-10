package com.discord.gateway.io

data class DeletedEntityIds(
    val guildId: Long,
    val channelIds: List<Long>? = null,
    val roleIds: List<Long>? = null,
    val emojiIds: List<Long>? = null,
    val stickerIds: List<Long>? = null,
)

internal fun guildEntityIdsHash(ids: Collection<Long>): String =
    (murmur3(ids.sorted().joinToString(",")).toLong() and 0xFFFFFFFFL).toString()

private fun murmur3(value: String): Int {
    val bytes = value.toByteArray(Charsets.UTF_8)
    var hash = 0
    var index = 0
    while (index + 4 <= bytes.size) {
        var chunk = (bytes[index].toInt() and 0xff) or ((bytes[index + 1].toInt() and 0xff) shl 8) or
            ((bytes[index + 2].toInt() and 0xff) shl 16) or ((bytes[index + 3].toInt() and 0xff) shl 24)
        chunk *= -862048943
        chunk = Integer.rotateLeft(chunk, 15) * 461845907
        hash = Integer.rotateLeft(hash xor chunk, 13) * 5 - 430675100
        index += 4
    }
    var tail = 0
    for (offset in bytes.lastIndex downTo index) tail = (tail shl 8) or (bytes[offset].toInt() and 0xff)
    if (index < bytes.size) {
        tail *= -862048943
        hash = hash xor (Integer.rotateLeft(tail, 15) * 461845907)
    }
    hash = hash xor bytes.size
    hash = (hash xor (hash ushr 16)) * -2048144789
    hash = (hash xor (hash ushr 13)) * -1028477387
    return hash xor (hash ushr 16)
}
