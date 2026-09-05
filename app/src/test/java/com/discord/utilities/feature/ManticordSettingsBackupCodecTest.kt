package com.discord.utilities.feature

import com.discord.utilities.feature.ManticordSettingsBackupCodec.Payload
import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ManticordSettingsBackupCodecTest {
    private val payload = Payload(mapOf("enabled" to true), mapOf("layout" to "compact"))
    private val json = """{"client":"manticord","version":1,"booleans":{"enabled":true},"strings":{"layout":"compact"}}"""

    @Test
    fun emptyPathsRoundTripAndOwnedPayloadCanBeReplaced() {
        val empty = UnknownFieldSet.getDefaultInstance()
        val inputs = listOf(
            empty,
            message(2 to bytes(ByteString.EMPTY)),
            message(2 to bytes(message(2 to bytes(ByteString.EMPTY)).toByteString())),
            withLeaf(ByteString.EMPTY),
        )
        for (input in inputs) {
            assertNull(ManticordSettingsBackupCodec.decode(input.encoded()))
            val patch = ManticordSettingsBackupCodec.encodeForUpload(input.encoded(), payload)
            assertEquals(0, patch.requiredDataVersion)
            assertEquals(payload, ManticordSettingsBackupCodec.decode(patch.settings))
            assertEquals(json, parse(patch.settings).child(2).child(2).getField(1).lengthDelimitedList.single().toStringUtf8())
        }
        val special = Payload(mapOf("disabled" to false), mapOf("" to "", "quote\"" to "\n\u00e9\uD83D\uDE80"))
        val updated = ManticordSettingsBackupCodec.encodeForUpload(withLeaf(ByteString.copyFromUtf8(json)).encoded(), special)
        assertEquals(special, ManticordSettingsBackupCodec.decode(updated.settings))
        assertEquals("Payload(booleans=<redacted>, strings=<redacted>)", special.toString())
        val cleared = ManticordSettingsBackupCodec.encodeForUpload(updated.settings, Payload(emptyMap(), emptyMap()))
        assertEquals(Payload(emptyMap(), emptyMap()), ManticordSettingsBackupCodec.decode(cleared.settings))
    }

    @Test
    fun patchCarriesCasAndPreservesEveryUnknownSiblingInsideFieldTwo() {
        val opaque = ByteString.copyFrom(byteArrayOf(0xff.toByte(), 0, 0x80.toByte()))
        val sibling = UnknownFieldSet.Field.newBuilder()
            .addVarint(91).addVarint(92).addFixed32(123).addFixed64(456)
            .addLengthDelimited(opaque).addLengthDelimited(ByteString.EMPTY)
            .addGroup(message(7 to varint(8))).build()
        val inner = message(1 to bytes(ByteString.copyFromUtf8(json)), 31 to sibling)
        val outer = message(1 to bytes(opaque), 2 to bytes(inner.toByteString()), 42 to sibling)
        val versions = message(3 to varint(123), 9 to sibling)
        val input = message(1 to bytes(versions.toByteString()), 2 to bytes(outer.toByteString()), 99 to sibling)

        val patch = ManticordSettingsBackupCodec.encodeForUpload(input.encoded(), payload.copy(strings = emptyMap()))
        val result = parse(patch.settings)
        assertEquals(123, patch.requiredDataVersion)
        assertEquals(setOf(2), result.asMap().keys)
        assertEquals(outer.getField(1), result.child(2).getField(1))
        assertEquals(sibling, result.child(2).getField(42))
        assertEquals(sibling, result.child(2).child(2).getField(31))
        assertEquals(payload.copy(strings = emptyMap()), ManticordSettingsBackupCodec.decode(patch.settings))

        val missingVersion = message(1 to bytes(message(9 to sibling).toByteString()))
        assertEquals(0, ManticordSettingsBackupCodec.encodeForUpload(missingVersion.encoded(), payload).requiredDataVersion)
        val largestSupported = message(1 to bytes(message(3 to varint(Int.MAX_VALUE.toLong())).toByteString()))
        assertEquals(Int.MAX_VALUE, ManticordSettingsBackupCodec.encodeForUpload(largestSupported.encoded(), payload).requiredDataVersion)
        val unsignedVersion = message(1 to bytes(message(3 to varint(0xffff_ffffL)).toByteString()))
        assertNull(ManticordSettingsBackupCodec.decode(unsignedVersion.encoded()))
        assertThrows(IllegalArgumentException::class.java) {
            ManticordSettingsBackupCodec.encodeForUpload(unsignedVersion.encoded(), payload)
        }
    }

    @Test
    fun foreignNewerAndMalformedJsonCannotBeReadOrOverwritten() {
        val invalid = listOf(
            "foreign opaque data", " ", "null", "[]", "{}",
            json.replace("manticord", "other-client"),
            json.replace("\"version\":1", "\"version\":2"),
            json.replace("\"version\":1", "\"version\":0"),
            json.replace("\"version\":1", "\"version\":\"1\""),
            json.replace("\"version\":1", "\"version\":1.0"),
            json.replace("\"client\":\"manticord\",", ""),
            json.dropLast(1) + ",\"future\":true}",
            json.dropLast(1) + ",\"version\":1}",
            json.replace("true", "\"true\""),
            json.replace("true", "1"),
            json.replace("true", "null"),
            json.replace("true", "TRUE"),
            json.replace("\"compact\"", "false"),
            json.replace("\"compact\"", "123"),
            json.replace("\"compact\"", "null"),
            json.replace("\"compact\"", "[]"),
            json.replace("\"layout\"", "\"enabled\""),
            json.replace("\"enabled\":true", "\"enabled\":true,\"enabled\":false"),
            json.replace("\"layout\":\"compact\"", "\"layout\":\"compact\",\"layout\":\"wide\""),
            json.replace("\"client\"", "'client'"),
            json.replace("\"client\"", "client"),
            "/* comment */$json", "$json {}", json.dropLast(1),
            json.replace("\"compact\"", "[".repeat(1000) + "0" + "]".repeat(1000)),
        )
        invalid.forEach { assertRejected(withLeaf(ByteString.copyFromUtf8(it)).encoded()) }
        assertRejected(withLeaf(ByteString.copyFrom(byteArrayOf(0xc3.toByte(), 0x28))).encoded())
    }

    @Test
    fun explicitClearAcceptsForeignBytesButPreservesSiblingsAndUsesCas() {
        val sibling = bytes(ByteString.copyFromUtf8("sibling fixture"))
        val inner = message(1 to bytes(ByteString.copyFrom(byteArrayOf(0xff.toByte(), 0))), 9 to sibling)
        val outer = message(2 to bytes(inner.toByteString()), 8 to sibling)
        val original = message(1 to bytes(message(3 to varint(7)).toByteString()),
            2 to bytes(outer.toByteString()), 20 to sibling)
        val patch = ManticordSettingsBackupCodec.clearForUpload(original.encoded())
        assertEquals(7, patch.requiredDataVersion)
        assertNull(ManticordSettingsBackupCodec.decode(patch.settings))
        val cleared = parse(patch.settings)
        assertEquals(setOf(2), cleared.asMap().keys)
        assertEquals(sibling, cleared.child(2).getField(8))
        assertEquals(sibling, cleared.child(2).child(2).getField(9))
        assertEquals(0, ManticordSettingsBackupCodec.clearForUpload("").requiredDataVersion)
        assertThrows(IllegalArgumentException::class.java) { ManticordSettingsBackupCodec.clearForUpload("!") }
        assertThrows(IllegalArgumentException::class.java) {
            ManticordSettingsBackupCodec.clearForUpload(message(2 to varint(1)).encoded())
        }
    }

    @Test
    fun malformedOrAmbiguousWirePathsFailClosed() {
        listOf("!", "=", " ", "AA==", byteArrayOf(0x12, 0x02, 0x01).toByteString().base64(),
            byteArrayOf(0x0c).toByteString().base64()).forEach(::assertRejected)
        val wrongFields = listOf(
            varint(1),
            UnknownFieldSet.Field.newBuilder().addFixed32(1).build(),
            UnknownFieldSet.Field.newBuilder().addFixed64(1).build(),
            UnknownFieldSet.Field.newBuilder().addGroup(UnknownFieldSet.getDefaultInstance()).build(),
            bytes(ByteString.EMPTY, ByteString.EMPTY),
            UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).addVarint(1).build(),
        )
        for (field in wrongFields) {
            assertRejected(message(1 to field).encoded())
            assertRejected(message(2 to field).encoded())
            assertRejected(message(2 to bytes(message(2 to field).toByteString())).encoded())
            assertRejected(message(2 to bytes(message(2 to bytes(message(1 to field).toByteString())).toByteString())).encoded())
        }
        listOf(bytes(ByteString.EMPTY), varint(1, 2), varint(-1), varint(0x1_0000_0000L)).forEach { version ->
            assertRejected(message(1 to bytes(message(3 to version).toByteString())).encoded())
        }
        assertRejected(message(2 to bytes(ByteString.copyFrom(byteArrayOf(0x12, 0x01)))).encoded())
        var groups = UnknownFieldSet.getDefaultInstance()
        repeat(4) { groups = message(9 to UnknownFieldSet.Field.newBuilder().addGroup(groups).build()) }
        assertRejected(groups.encoded())
    }

    @Test
    fun entryAndByteLimitsAreInclusiveAndEnforcedBothWays() {
        val maxEntries = Payload((0 until 8192).associate { "key$it" to true }, emptyMap())
        assertEquals(maxEntries, ManticordSettingsBackupCodec.decode(ManticordSettingsBackupCodec.encodeForUpload("", maxEntries).settings))
        val tooMany = maxEntries.copy(strings = mapOf("one-more" to "value"))
        assertThrows(IllegalArgumentException::class.java) { ManticordSettingsBackupCodec.encodeForUpload("", tooMany) }
        val tooManyJson = """{"client":"manticord","version":1,"booleans":{${(0..8192).joinToString(",") { "\"key$it\":true" }}},"strings":{}}"""
        assertRejected(withLeaf(ByteString.copyFromUtf8(tooManyJson)).encoded())
        assertThrows(IllegalArgumentException::class.java) {
            ManticordSettingsBackupCodec.encodeForUpload("", Payload(mapOf("duplicate" to true), mapOf("duplicate" to "value")))
        }

        val emptyStringJson = """{"client":"manticord","version":1,"booleans":{},"strings":{"text":""}}"""
        val limit = 3 * 1024 * 1024
        val available = limit - emptyStringJson.toByteArray(Charsets.UTF_8).size
        val exact = Payload(emptyMap(), mapOf("text" to "x".repeat(available)))
        val patch = ManticordSettingsBackupCodec.encodeForUpload("", exact)
        assertEquals(limit, parse(patch.settings).child(2).child(2).getField(1).lengthDelimitedList.single().size())
        assertEquals(exact, ManticordSettingsBackupCodec.decode(patch.settings))
        for (value in listOf("x".repeat(available + 1), "\u00e9".repeat(available), "\n".repeat(available))) {
            assertThrows(IllegalArgumentException::class.java) {
                ManticordSettingsBackupCodec.encodeForUpload("", Payload(emptyMap(), mapOf("text" to value)))
            }
        }
        assertRejected(withLeaf(ByteString.copyFromUtf8(emptyStringJson.replace("\"text\":\"\"", "\"text\":\"${"x".repeat(available + 1)}\""))).encoded())
        assertRejected("A".repeat(5_242_881))
        assertThrows(IllegalArgumentException::class.java) {
            ManticordSettingsBackupCodec.encodeForUpload("", Payload(emptyMap(), mapOf("text" to "\uD800")))
        }
    }

    private fun assertRejected(encoded: String) {
        assertThrows(IllegalArgumentException::class.java) { ManticordSettingsBackupCodec.decode(encoded) }
        assertThrows(IllegalArgumentException::class.java) { ManticordSettingsBackupCodec.encodeForUpload(encoded, payload) }
    }

    private fun withLeaf(leaf: ByteString): UnknownFieldSet =
        message(2 to bytes(message(2 to bytes(message(1 to bytes(leaf)).toByteString())).toByteString()))

    private fun message(vararg fields: Pair<Int, UnknownFieldSet.Field>): UnknownFieldSet =
        UnknownFieldSet.newBuilder().apply { fields.forEach { (number, field) -> addField(number, field) } }.build()

    private fun bytes(vararg values: ByteString): UnknownFieldSet.Field =
        UnknownFieldSet.Field.newBuilder().apply { values.forEach { addLengthDelimited(it) } }.build()

    private fun varint(vararg values: Long): UnknownFieldSet.Field =
        UnknownFieldSet.Field.newBuilder().apply { values.forEach { addVarint(it) } }.build()

    private fun UnknownFieldSet.child(number: Int): UnknownFieldSet =
        UnknownFieldSet.parseFrom(getField(number).lengthDelimitedList.single())

    private fun UnknownFieldSet.encoded() = toByteArray().toByteString().base64()

    private fun parse(encoded: String) = UnknownFieldSet.parseFrom(encoded.decodeBase64()!!.toByteArray())
}
