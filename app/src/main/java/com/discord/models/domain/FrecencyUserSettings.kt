package com.discord.models.domain

import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

data class FrecencyUserSettings(
    val versions: Versions? = null,
    val favoriteGifs: FavoriteGifs? = null,
    val favoriteEmojis: FavoriteEmojis? = null,
    val emojiFrecency: EmojiFrecency? = null,
) {
    data class Versions(
        val clientVersion: Int = 0,
        val serverVersion: Int = 0,
        val dataVersion: Int = 0,
    )

    data class FavoriteGif(
        val format: GifType = GifType.NONE,
        val src: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val order: Int = 0,
    )

    data class FavoriteGifs(
        val gifs: Map<String, FavoriteGif> = emptyMap(),
        val hideTooltip: Boolean = false,
    )

    data class FavoriteEmojis(val emojis: List<String> = emptyList())

    data class FrecencyItem(
        val totalUses: Int = 0,
        val recentUses: List<Long> = emptyList(),
        val frecency: Int = 0,
        val score: Int = 0,
    )

    data class EmojiFrecency(val emojis: Map<String, FrecencyItem> = emptyMap())

    enum class GifType(val wireValue: Int) {
        NONE(0),
        IMAGE(1),
        VIDEO(2),
        ;

        companion object {
            fun fromWireValue(value: Int) = entries.firstOrNull { it.wireValue == value } ?: NONE
        }
    }

    fun mergePartial(update: FrecencyUserSettings) = copy(
        versions = update.versions ?: versions,
        favoriteGifs = update.favoriteGifs ?: favoriteGifs,
        favoriteEmojis = update.favoriteEmojis ?: favoriteEmojis,
        emojiFrecency = update.emojiFrecency ?: emojiFrecency,
    )
}

data class UserSettingsProtoEnvelope(val proto: String, val type: Int)

data class UserSettingsProtoUpdate(
    val settings: UserSettingsProtoEnvelope,
    val partial: Boolean,
)

data class UserSettingsProtoResponse(
    val settings: String,
    @SerializedName("out_of_date") val outOfDate: Boolean = false,
)

data class UserSettingsProtoPatch(
    val settings: String,
    @SerializedName("required_data_version") val requiredDataVersion: Int? = null,
)

/** Minimal protobuf codec for the four type-2 fields this store owns. Unknown fields are skipped. */
object FrecencyUserSettingsCodec {
    fun decodeBase64(encoded: String): FrecencyUserSettings =
        decode(encoded.decodeBase64()?.toByteArray() ?: throw IllegalArgumentException("Invalid settings encoding"))

    fun encodeBase64(settings: FrecencyUserSettings): String = encode(settings).toByteString().base64()

    fun decode(bytes: ByteArray): FrecencyUserSettings {
        val reader = Reader(bytes)
        var versions: FrecencyUserSettings.Versions? = null
        var favoriteGifs: FrecencyUserSettings.FavoriteGifs? = null
        var favoriteEmojis: FrecencyUserSettings.FavoriteEmojis? = null
        var emojiFrecency: FrecencyUserSettings.EmojiFrecency? = null
        while (!reader.exhausted()) {
            val tag = reader.varint().toInt()
            when (tag ushr 3) {
                1 -> versions = decodeVersions(reader.message(tag))
                2 -> favoriteGifs = decodeFavoriteGifs(reader.message(tag))
                5 -> favoriteEmojis = decodeFavoriteEmojis(reader.message(tag))
                6 -> emojiFrecency = decodeEmojiFrecency(reader.message(tag))
                else -> reader.skip(tag and 7)
            }
        }
        return FrecencyUserSettings(versions, favoriteGifs, favoriteEmojis, emojiFrecency)
    }

    fun encode(settings: FrecencyUserSettings): ByteArray = Writer().apply {
        settings.versions?.let { message(1, encodeVersions(it)) }
        settings.favoriteGifs?.let { message(2, encodeFavoriteGifs(it)) }
        settings.favoriteEmojis?.let { message(5, encodeFavoriteEmojis(it)) }
        settings.emojiFrecency?.let { message(6, encodeEmojiFrecency(it)) }
    }.bytes()

    private fun decodeVersions(reader: Reader): FrecencyUserSettings.Versions {
        var client = 0
        var server = 0
        var data = 0
        while (!reader.exhausted()) {
            val tag = reader.varint().toInt()
            when (tag ushr 3) {
                1 -> client = reader.varint().toInt()
                2 -> server = reader.varint().toInt()
                3 -> data = reader.varint().toInt()
                else -> reader.skip(tag and 7)
            }
        }
        return FrecencyUserSettings.Versions(client, server, data)
    }

    private fun encodeVersions(value: FrecencyUserSettings.Versions) = Writer().apply {
        uint32(1, value.clientVersion)
        uint32(2, value.serverVersion)
        uint32(3, value.dataVersion)
    }.bytes()

    private fun decodeFavoriteGifs(reader: Reader): FrecencyUserSettings.FavoriteGifs {
        val gifs = LinkedHashMap<String, FrecencyUserSettings.FavoriteGif>()
        var hideTooltip = false
        while (!reader.exhausted()) {
            val tag = reader.varint().toInt()
            when (tag ushr 3) {
                1 -> {
                    val entry = reader.message(tag)
                    var key = ""
                    var value = FrecencyUserSettings.FavoriteGif()
                    while (!entry.exhausted()) {
                        val entryTag = entry.varint().toInt()
                        when (entryTag ushr 3) {
                            1 -> key = entry.string()
                            2 -> value = decodeFavoriteGif(entry.message(entryTag))
                            else -> entry.skip(entryTag and 7)
                        }
                    }
                    gifs[key] = value
                }
                2 -> hideTooltip = reader.varint() != 0L
                else -> reader.skip(tag and 7)
            }
        }
        return FrecencyUserSettings.FavoriteGifs(gifs, hideTooltip)
    }

    private fun encodeFavoriteGifs(value: FrecencyUserSettings.FavoriteGifs) = Writer().apply {
        value.gifs.forEach { (key, gif) ->
            message(1, Writer().apply {
                string(1, key)
                message(2, encodeFavoriteGif(gif))
            }.bytes())
        }
        bool(2, value.hideTooltip)
    }.bytes()

    private fun decodeFavoriteGif(reader: Reader): FrecencyUserSettings.FavoriteGif {
        var format = FrecencyUserSettings.GifType.NONE
        var src = ""
        var width = 0
        var height = 0
        var order = 0
        while (!reader.exhausted()) {
            val tag = reader.varint().toInt()
            when (tag ushr 3) {
                1 -> format = FrecencyUserSettings.GifType.fromWireValue(reader.varint().toInt())
                2 -> src = reader.string()
                3 -> width = reader.varint().toInt()
                4 -> height = reader.varint().toInt()
                5 -> order = reader.varint().toInt()
                else -> reader.skip(tag and 7)
            }
        }
        return FrecencyUserSettings.FavoriteGif(format, src, width, height, order)
    }

    private fun encodeFavoriteGif(value: FrecencyUserSettings.FavoriteGif) = Writer().apply {
        uint32(1, value.format.wireValue)
        string(2, value.src)
        uint32(3, value.width)
        uint32(4, value.height)
        uint32(5, value.order)
    }.bytes()

    private fun decodeFavoriteEmojis(reader: Reader): FrecencyUserSettings.FavoriteEmojis {
        val emojis = ArrayList<String>()
        while (!reader.exhausted()) {
            val tag = reader.varint().toInt()
            if (tag ushr 3 == 1) emojis += reader.string() else reader.skip(tag and 7)
        }
        return FrecencyUserSettings.FavoriteEmojis(emojis)
    }

    private fun encodeFavoriteEmojis(value: FrecencyUserSettings.FavoriteEmojis) = Writer().apply {
        value.emojis.forEach { string(1, it) }
    }.bytes()

    private fun decodeEmojiFrecency(reader: Reader): FrecencyUserSettings.EmojiFrecency {
        val emojis = LinkedHashMap<String, FrecencyUserSettings.FrecencyItem>()
        while (!reader.exhausted()) {
            val tag = reader.varint().toInt()
            if (tag ushr 3 != 1) {
                reader.skip(tag and 7)
                continue
            }
            val entry = reader.message(tag)
            var key = ""
            var value = FrecencyUserSettings.FrecencyItem()
            while (!entry.exhausted()) {
                val entryTag = entry.varint().toInt()
                when (entryTag ushr 3) {
                    1 -> key = entry.string()
                    2 -> value = decodeFrecencyItem(entry.message(entryTag))
                    else -> entry.skip(entryTag and 7)
                }
            }
            emojis[key] = value
        }
        return FrecencyUserSettings.EmojiFrecency(emojis)
    }

    private fun encodeEmojiFrecency(value: FrecencyUserSettings.EmojiFrecency) = Writer().apply {
        value.emojis.forEach { (key, item) ->
            message(1, Writer().apply {
                string(1, key)
                message(2, encodeFrecencyItem(item))
            }.bytes())
        }
    }.bytes()

    private fun decodeFrecencyItem(reader: Reader): FrecencyUserSettings.FrecencyItem {
        var totalUses = 0
        val recentUses = ArrayList<Long>()
        var frecency = 0
        var score = 0
        while (!reader.exhausted()) {
            val tag = reader.varint().toInt()
            when (tag ushr 3) {
                1 -> totalUses = reader.varint().toInt()
                2 -> if ((tag and 7) == 2) {
                    val packed = reader.message(tag)
                    while (!packed.exhausted()) recentUses += packed.varint()
                } else {
                    recentUses += reader.varint()
                }
                3 -> frecency = reader.varint().toInt()
                4 -> score = reader.varint().toInt()
                else -> reader.skip(tag and 7)
            }
        }
        return FrecencyUserSettings.FrecencyItem(totalUses, recentUses, frecency, score)
    }

    private fun encodeFrecencyItem(value: FrecencyUserSettings.FrecencyItem) = Writer().apply {
        uint32(1, value.totalUses)
        if (value.recentUses.isNotEmpty()) {
            message(2, Writer().apply { value.recentUses.forEach(::varint) }.bytes())
        }
        int32(3, value.frecency)
        int32(4, value.score)
    }.bytes()

    private class Writer {
        private val output = ByteArrayOutputStream()

        fun uint32(field: Int, value: Int) {
            if (value != 0) {
                tag(field, 0)
                varint(value.toLong() and 0xffffffffL)
            }
        }

        fun int32(field: Int, value: Int) {
            if (value != 0) {
                tag(field, 0)
                varint(value.toLong())
            }
        }

        fun bool(field: Int, value: Boolean) {
            if (value) {
                tag(field, 0)
                varint(1)
            }
        }

        fun string(field: Int, value: String) {
            if (value.isNotEmpty()) message(field, value.toByteArray(Charsets.UTF_8))
        }

        fun message(field: Int, value: ByteArray) {
            tag(field, 2)
            varint(value.size.toLong())
            output.write(value)
        }

        fun varint(value: Long) {
            var remaining = value
            while (remaining and -128L != 0L) {
                output.write(((remaining and 127L) or 128L).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }

        fun bytes(): ByteArray = output.toByteArray()

        private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        fun exhausted() = position >= bytes.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64 && position < bytes.size) {
                val value = bytes[position].toInt() and 0xff
                position++
                result = result or ((value and 0x7f).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Malformed protobuf varint")
        }

        fun message(tag: Int): Reader {
            require(tag and 7 == 2) { "Expected length-delimited protobuf field ${tag ushr 3}" }
            val length = varint().toInt()
            require(length >= 0 && position + length <= bytes.size) { "Malformed protobuf length" }
            val message = Reader(bytes.copyOfRange(position, position + length))
            position += length
            return message
        }

        fun string(): String {
            val length = varint().toInt()
            require(length >= 0 && position + length <= bytes.size) { "Malformed protobuf string" }
            return bytes.copyOfRange(position, position + length).toString(Charsets.UTF_8).also { position += length }
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> position += 8
                2 -> {
                    val length = varint().toInt()
                    position += length
                }
                5 -> position += 4
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $wireType")
            }
            require(position <= bytes.size) { "Malformed protobuf field" }
        }
    }
}
