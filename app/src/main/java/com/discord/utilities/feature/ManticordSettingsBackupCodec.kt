package com.discord.utilities.feature

import com.discord.models.domain.UserSettingsProtoPatch
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import java.io.IOException
import java.io.StringReader
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * Type-3 backups use only the runtime-verified length-delimited path [2, 2, 1].
 * Input is canonical Base64, at most 5,242,880 characters; our JSON is at most 3 MiB
 * of UTF-8 and 8192 preferences. Preference allowlisting belongs to the caller.
 * Unknown sibling values stay in UnknownFieldSet; opaque sibling bytes are never decoded.
 */
object ManticordSettingsBackupCodec {
    class UnrecognizedCloudData : IllegalArgumentException("Existing cloud data is not a supported Microcord backup")

    private const val MAX_ENCODED_CHARS = 5_242_880
    private const val MAX_JSON_BYTES = 3 * 1024 * 1024
    private const val MAX_ENTRIES = 8192

    data class Payload(val booleans: Map<String, Boolean>, val strings: Map<String, String>) {
        override fun toString() = "Payload(booleans=<redacted>, strings=<redacted>)"
    }

    /** Returns null only for an absent/zero-byte leaf; all invalid or foreign data throws. */
    fun decode(encoded: String): Payload? {
        val root = readEncoded(encoded)
        dataVersion(root)
        return readPayload(root.message(2).message(2).lengthDelimited(1))
    }

    /**
     * Replaces only field 2, preserving its siblings below that boundary and refusing foreign data.
     * CAS is mandatory. Until UserSettingsProtoPatch supports unsigned versions, uploads with
     * data_version above Int.MAX_VALUE fail closed rather than sending a truncated/negative value.
     */
    fun encodeForUpload(encoded: String, payload: Payload): UserSettingsProtoPatch {
        val root = readEncoded(encoded)
        val version = dataVersion(root)
        require(version <= Int.MAX_VALUE) { "Settings data version exceeds the patch model's range" }
        val outer = root.message(2)
        val inner = outer.message(2)
        readPayload(inner.lengthDelimited(1))

        require(payload.booleans.size.toLong() + payload.strings.size <= MAX_ENTRIES) {
            "Too many backup preferences"
        }
        require(payload.booleans.keys.none { it in payload.strings }) { "Duplicate backup preference" }
        // Bound allocation before escaping JSON, then enforce the actual UTF-8 byte limit below.
        val characters = payload.booleans.keys.sumOf { it.length.toLong() } +
            payload.strings.entries.sumOf { it.key.length.toLong() + it.value.length }
        require(characters <= MAX_JSON_BYTES) { "Backup JSON exceeds 3 MiB" }
        val text = JsonObject().apply {
            addProperty("client", "manticord")
            addProperty("version", 1)
            add("booleans", JsonObject().apply { payload.booleans.forEach { (key, value) -> addProperty(key, value) } })
            add("strings", JsonObject().apply { payload.strings.forEach { (key, value) -> addProperty(key, value) } })
        }.toString()
        val json = try {
            ByteString.copyFrom(Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text)))
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Backup contains invalid Unicode")
        }
        require(json.size() <= MAX_JSON_BYTES) { "Backup JSON exceeds 3 MiB" }
        val updated = outer.withBytes(2, inner.withBytes(1, json).toByteString())
        val patch = UnknownFieldSet.getDefaultInstance().withBytes(2, updated.toByteString())
        val settings = patch.toByteArray().toByteString().base64()
        require(settings.length <= MAX_ENCODED_CHARS) { "Encoded backup exceeds input limit" }
        return UserSettingsProtoPatch(settings = settings, requiredDataVersion = version.toInt())
    }

    /** Explicit user-approved deletion; clear only the known leaf without interpreting its contents. */
    fun clearForUpload(encoded: String): UserSettingsProtoPatch {
        val root = readEncoded(encoded)
        val version = dataVersion(root)
        require(version <= Int.MAX_VALUE) { "Settings data version exceeds the patch model's range" }
        val outer = root.message(2)
        val inner = outer.message(2)
        inner.lengthDelimited(1) // Reject an ambiguous path, even for an explicit clear.
        val updated = outer.withBytes(2, inner.withBytes(1, ByteString.EMPTY).toByteString())
        val settings = UnknownFieldSet.getDefaultInstance().withBytes(2, updated.toByteString())
            .toByteArray().toByteString().base64()
        require(settings.length <= MAX_ENCODED_CHARS) { "Encoded backup exceeds input limit" }
        return UserSettingsProtoPatch(settings, version.toInt())
    }

    private fun readEncoded(encoded: String): UnknownFieldSet {
        require(encoded.length <= MAX_ENCODED_CHARS) { "Encoded backup exceeds input limit" }
        val bytes = encoded.decodeBase64() ?: throw IllegalArgumentException("Invalid backup Base64")
        require(bytes.base64() == encoded) { "Invalid backup Base64" }
        return readMessage(ByteString.copyFrom(bytes.toByteArray()))
    }

    private fun readMessage(bytes: ByteString): UnknownFieldSet = try {
        val input = bytes.newCodedInput()
        // No speculative traversal: only the fixed path is decoded; groups are bounded too.
        input.setRecursionLimit(3)
        UnknownFieldSet.parseFrom(input).also {
            input.checkLastTagWas(0)
            require(input.isAtEnd) { "Malformed settings protobuf" }
        }
    } catch (_: IOException) {
        throw IllegalArgumentException("Malformed settings protobuf")
    }

    private fun UnknownFieldSet.lengthDelimited(number: Int): ByteString? {
        val field = getField(number)
        require(field.lengthDelimitedList.size <= 1 && field.varintList.isEmpty() &&
            field.fixed32List.isEmpty() && field.fixed64List.isEmpty() && field.groupList.isEmpty()) {
            "Ambiguous settings protobuf path"
        }
        return field.lengthDelimitedList.singleOrNull()
    }

    private fun UnknownFieldSet.message(number: Int) =
        readMessage(lengthDelimited(number) ?: ByteString.EMPTY)

    private fun UnknownFieldSet.withBytes(number: Int, bytes: ByteString): UnknownFieldSet =
        toBuilder().clearField(number)
            .addField(number, UnknownFieldSet.Field.newBuilder().addLengthDelimited(bytes).build()).build()

    private fun dataVersion(root: UnknownFieldSet): Long {
        val field = root.message(1).getField(3)
        require(field.varintList.size <= 1 && field.lengthDelimitedList.isEmpty() &&
            field.fixed32List.isEmpty() && field.fixed64List.isEmpty() && field.groupList.isEmpty()) {
            "Ambiguous settings data version"
        }
        val version = field.varintList.singleOrNull() ?: 0L
        require(version in 0L..0xffff_ffffL) { "Invalid uint32 settings data version" }
        return version
    }

    private fun readPayload(bytes: ByteString?): Payload? {
        if (bytes == null || bytes.isEmpty) return null
        try {
            require(bytes.size() <= MAX_JSON_BYTES)
            require(bytes.isValidUtf8)
            JsonReader(StringReader(bytes.toStringUtf8())).use { reader ->
                reader.strictness = Strictness.STRICT
                val fields = HashSet<String>()
                val booleans = linkedMapOf<String, Boolean>()
                val strings = linkedMapOf<String, String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(fields.add(name))
                    when (name) {
                        "client" -> require(reader.peek() == JsonToken.STRING && reader.nextString() == "manticord")
                        "version" -> require(reader.peek() == JsonToken.NUMBER && reader.nextString() == "1")
                        "booleans", "strings" -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                require(booleans.size + strings.size < MAX_ENTRIES)
                                val key = reader.nextName()
                                require(key !in booleans && key !in strings)
                                if (name == "booleans") {
                                    require(reader.peek() == JsonToken.BOOLEAN)
                                    booleans[key] = reader.nextBoolean()
                                } else {
                                    require(reader.peek() == JsonToken.STRING)
                                    strings[key] = reader.nextString()
                                }
                            }
                            reader.endObject()
                        }
                        else -> throw IllegalArgumentException()
                    }
                }
                reader.endObject()
                require(fields.size == 4 && reader.peek() == JsonToken.END_DOCUMENT)
                return Payload(booleans, strings)
            }
        } catch (_: Exception) {
            // Parser diagnostics can contain preference keys/values; do not retain their causes.
            throw UnrecognizedCloudData()
        }
    }
}
