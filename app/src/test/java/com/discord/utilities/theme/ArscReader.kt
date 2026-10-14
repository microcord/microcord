package com.discord.utilities.theme

import com.discord.utilities.theme.ArscWriter.CONFIG_DENSITY_OFFSET
import com.discord.utilities.theme.ArscWriter.CONFIG_SDK_OFFSET
import com.discord.utilities.theme.ArscWriter.CONFIG_SIZE
import com.discord.utilities.theme.ArscWriter.FLAG_COMPLEX
import com.discord.utilities.theme.ArscWriter.NO_ENTRY
import com.discord.utilities.theme.ArscWriter.PACKAGE_HEADER
import com.discord.utilities.theme.ArscWriter.PACKAGE_NAME_CHARS
import com.discord.utilities.theme.ArscWriter.RES_STRING_POOL
import com.discord.utilities.theme.ArscWriter.RES_TABLE
import com.discord.utilities.theme.ArscWriter.RES_TABLE_PACKAGE
import com.discord.utilities.theme.ArscWriter.RES_TABLE_TYPE
import com.discord.utilities.theme.ArscWriter.RES_TABLE_TYPE_SPEC
import com.discord.utilities.theme.ArscWriter.STRING_POOL_HEADER
import com.discord.utilities.theme.ArscWriter.TYPE_HEADER
import com.discord.utilities.theme.ArscWriter.TYPE_INT_COLOR_ARGB8
import com.discord.utilities.theme.ArscWriter.TYPE_STRING
import com.discord.utilities.theme.ArscWriter.TYPE_SPEC_HEADER
import com.discord.utilities.theme.ArscWriter.UTF8_FLAG
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads back a table written by [ArscWriter], checking every size, offset and alignment on the way.
 *
 * The tests' way to check the writer's output. It only understands what the
 * writer produces: one package, UTF-8 pools, dense type chunks, and color, color-style or file values.
 */
object ArscReader {

    class Table(val packageId: Int, val packageName: String, val types: Map<Int, Type>)

    /** [configs] maps each configuration to that chunk's entries, keyed by entry id. */
    class Type(val name: String, val configs: Map<ArscWriter.Config, Map<Int, Entry>>)

    class Entry(val key: String, val value: ArscWriter.Value)

    /** Throws [IllegalArgumentException] for anything malformed. */
    fun read(bytes: ByteArray): Table = try {
        parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    } catch (e: IndexOutOfBoundsException) {
        throw IllegalArgumentException("Truncated table", e)
    } catch (e: java.nio.BufferUnderflowException) {
        throw IllegalArgumentException("Truncated table", e)
    }

    private fun parse(buffer: ByteBuffer): Table {
        val table = header(buffer, 0, RES_TABLE, 12)
        require(table.size == buffer.limit()) { "Table size does not match the data" }
        require(buffer.getInt(8) == 1) { "Expected one package" }
        val globalPool = header(buffer, 12, RES_STRING_POOL, STRING_POOL_HEADER)
        val paths = strings(buffer, globalPool)
        val packageStart = 12 + globalPool.size
        val pkg = header(buffer, packageStart, RES_TABLE_PACKAGE, PACKAGE_HEADER)
        require(packageStart + pkg.size == table.size) { "Trailing data after the package" }
        val packageId = buffer.getInt(packageStart + 8)
        val name = buildString {
            for (i in 0 until PACKAGE_NAME_CHARS) {
                val c = buffer.getChar(packageStart + 12 + 2 * i)
                if (c == '\u0000') break
                append(c)
            }
        }
        val typeStrings = buffer.getInt(packageStart + 268)
        val keyStrings = buffer.getInt(packageStart + 276)
        val typePool = header(buffer, packageStart + typeStrings, RES_STRING_POOL, STRING_POOL_HEADER)
        val keyPool = header(buffer, packageStart + keyStrings, RES_STRING_POOL, STRING_POOL_HEADER)
        require(typeStrings == PACKAGE_HEADER && keyStrings == typeStrings + typePool.size) { "Unexpected pool offsets" }
        val typeNames = strings(buffer, typePool)
        val keys = strings(buffer, keyPool)

        val types = LinkedHashMap<Int, Type>()
        var offset = keyPool.start + keyPool.size
        var specEntries = -1
        var specId = -1
        val configs = LinkedHashMap<ArscWriter.Config, Map<Int, Entry>>()
        fun finishType() {
            if (specId < 0) return
            require(configs.isNotEmpty()) { "Type $specId has no configurations" }
            types[specId] = Type(typeNames[specId - 1], LinkedHashMap(configs))
            configs.clear()
        }
        while (offset < pkg.start + pkg.size) {
            val chunkType = buffer.getShort(offset).toInt() and 0xFFFF
            if (chunkType == RES_TABLE_TYPE_SPEC) {
                finishType()
                val spec = header(buffer, offset, RES_TABLE_TYPE_SPEC, TYPE_SPEC_HEADER)
                specId = buffer.get(offset + 8).toInt() and 0xFF
                require(specId in 1..typeNames.size && specId !in types) { "Bad type id $specId" }
                specEntries = buffer.getInt(offset + 12)
                require(spec.size == TYPE_SPEC_HEADER + 4 * specEntries) { "Type spec size mismatch" }
                offset += spec.size
            } else {
                val type = header(buffer, offset, RES_TABLE_TYPE, TYPE_HEADER)
                require((buffer.get(offset + 8).toInt() and 0xFF) == specId) { "Type chunk without its spec" }
                require(buffer.get(offset + 9).toInt() == 0) { "Sparse or unknown type flags" }
                val count = buffer.getInt(offset + 12)
                val entriesStart = buffer.getInt(offset + 16)
                require(count == specEntries && entriesStart == TYPE_HEADER + 4 * count) { "Type entry count mismatch" }
                require(buffer.getInt(offset + 20) == CONFIG_SIZE) { "Unexpected config size" }
                val config = ArscWriter.Config(
                    sdk = buffer.getShort(offset + 20 + CONFIG_SDK_OFFSET).toInt() and 0xFFFF,
                    density = buffer.getShort(offset + 20 + CONFIG_DENSITY_OFFSET).toInt() and 0xFFFF,
                )
                require(config !in configs) { "Duplicate configuration" }
                configs[config] = entries(buffer, offset, type.size, count, entriesStart, keys, paths)
                offset += type.size
            }
        }
        finishType()
        require(offset == pkg.start + pkg.size) { "Chunk overruns its package" }
        return Table(packageId, name, types)
    }

    private fun entries(
        buffer: ByteBuffer, chunk: Int, chunkSize: Int, count: Int, entriesStart: Int, keys: List<String>, paths: List<String>,
    ): Map<Int, Entry> {
        val out = LinkedHashMap<Int, Entry>()
        for (i in 0 until count) {
            val relative = buffer.getInt(chunk + TYPE_HEADER + 4 * i)
            if (relative == NO_ENTRY) continue
            val at = chunk + entriesStart + relative
            require(relative >= 0 && relative % 4 == 0 && at + 16 <= chunk + chunkSize) { "Entry $i out of bounds" }
            val size = buffer.getShort(at).toInt() and 0xFFFF
            val flags = buffer.getShort(at + 2).toInt() and 0xFFFF
            val key = keys.getOrNull(buffer.getInt(at + 4)) ?: throw IllegalArgumentException("Entry $i has no key")
            val value = if (flags and FLAG_COMPLEX != 0) {
                require(size == 16) { "Bad style entry size" }
                val itemCount = buffer.getInt(at + 12)
                require(itemCount >= 0 && at + 16 + 12L * itemCount <= chunk + chunkSize) { "Style overruns its chunk" }
                val items = LinkedHashMap<Int, Int>()
                var previous = Int.MIN_VALUE
                for (j in 0 until itemCount) {
                    val item = at + 16 + 12 * j
                    val attr = buffer.getInt(item)
                    require(attr > previous) { "Style items must be sorted by attribute" }
                    previous = attr
                    items[attr] = color(buffer, item + 4)
                }
                ArscWriter.Value.Style(items)
            } else {
                require(size == 8 && buffer.getShort(at + 8).toInt() == 8) { "Bad entry size" }
                when (buffer.get(at + 11).toInt()) {
                    TYPE_STRING -> ArscWriter.Value.File(
                        paths.getOrNull(buffer.getInt(at + 12)) ?: throw IllegalArgumentException("Entry $i has no path"),
                    )
                    else -> ArscWriter.Value.Color(color(buffer, at + 8))
                }
            }
            out[i] = Entry(key, value)
        }
        return out
    }

    private fun color(buffer: ByteBuffer, at: Int): Int {
        require(buffer.getShort(at).toInt() == 8 && buffer.get(at + 3).toInt() == TYPE_INT_COLOR_ARGB8) { "Not a color value" }
        return buffer.getInt(at + 4)
    }

    private fun strings(buffer: ByteBuffer, pool: Chunk): List<String> {
        val count = buffer.getInt(pool.start + 8)
        require(buffer.getInt(pool.start + 12) == 0) { "Styled strings are not supported" }
        require(buffer.getInt(pool.start + 16) == UTF8_FLAG) { "Expected a UTF-8 pool" }
        val stringsStart = buffer.getInt(pool.start + 20)
        require(stringsStart == STRING_POOL_HEADER + 4 * count && stringsStart <= pool.size) { "Bad strings start" }
        return (0 until count).map { i ->
            var at = pool.start + stringsStart + buffer.getInt(pool.start + STRING_POOL_HEADER + 4 * i)
            fun length(): Int {
                val first = buffer.get(at++).toInt() and 0xFF
                return if (first and 0x80 != 0) ((first and 0x7F) shl 8) or (buffer.get(at++).toInt() and 0xFF) else first
            }
            length()
            val byteLength = length()
            require(at + byteLength < pool.start + pool.size && buffer.get(at + byteLength).toInt() == 0) { "Bad string $i" }
            String(ByteArray(byteLength) { buffer.get(at + it) }, Charsets.UTF_8)
        }
    }

    private class Chunk(val start: Int, val size: Int)

    private fun header(buffer: ByteBuffer, start: Int, type: Int, headerSize: Int): Chunk {
        require(start % 4 == 0) { "Unaligned chunk" }
        require((buffer.getShort(start).toInt() and 0xFFFF) == type) { "Expected chunk ${Integer.toHexString(type)}" }
        require((buffer.getShort(start + 2).toInt() and 0xFFFF) == headerSize) { "Bad header size for ${Integer.toHexString(type)}" }
        val size = buffer.getInt(start + 4)
        require(size >= headerSize && size % 4 == 0 && start + size <= buffer.limit()) { "Bad chunk size" }
        return Chunk(start, size)
    }
}
