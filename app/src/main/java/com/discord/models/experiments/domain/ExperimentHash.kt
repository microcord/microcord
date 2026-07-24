package com.discord.models.experiments.domain

import java.nio.charset.StandardCharsets

object ExperimentHash {
    fun from(name: CharSequence): Long {
        val bytes = name.toString().toByteArray(StandardCharsets.UTF_8)
        var hash = 0
        var index = 0
        while (index + 4 <= bytes.size) {
            var value = (bytes[index].toInt() and 0xff) or
                ((bytes[index + 1].toInt() and 0xff) shl 8) or
                ((bytes[index + 2].toInt() and 0xff) shl 16) or
                ((bytes[index + 3].toInt() and 0xff) shl 24)
            value *= -862048943
            value = Integer.rotateLeft(value, 15)
            value *= 461845907
            hash = Integer.rotateLeft(hash xor value, 13) * 5 - 430675100
            index += 4
        }
        var tail = 0
        when (bytes.size - index) {
            3 -> tail = tail or ((bytes[index + 2].toInt() and 0xff) shl 16)
            else -> Unit
        }
        if (bytes.size - index >= 2) tail = tail or ((bytes[index + 1].toInt() and 0xff) shl 8)
        if (bytes.size - index >= 1) {
            tail = tail or (bytes[index].toInt() and 0xff)
            tail *= -862048943
            tail = Integer.rotateLeft(tail, 15)
            tail *= 461845907
            hash = hash xor tail
        }
        hash = hash xor bytes.size
        hash = hash xor (hash ushr 16)
        hash *= -2048144789
        hash = hash xor (hash ushr 13)
        hash *= -1028477387
        hash = hash xor (hash ushr 16)
        return hash.toLong() and 0xffffffffL
    }
}
