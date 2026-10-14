package com.discord.utilities.theme

import java.io.ByteArrayOutputStream

/**
 * Writes the small `resources.arsc` table a theme is loaded from.
 *
 * The table holds only the entries it overrides or adds. An override keeps its real package, type
 * and entry id and its real type and key names. A loader entry wins a lookup only when its
 * configuration is at least as good as the base entry's. That is why each entry carries the
 * configurations it is written in ([Entry.configs]).
 *
 * The layout follows `ResourceTypes.h`, little-endian throughout:
 * - The table header, then the global string pool, which holds file paths.
 * - One package chunk, containing:
 *   - the type-name pool, indexed by type id minus one;
 *   - the key-name pool;
 *   - per type, a type spec followed by one type chunk per configuration.
 *
 * [ArscReader] reads the result back and checks it.
 */
object ArscWriter {
    /** Part of the cached table's key; bump when the output changes. */
    const val VERSION = 3
    const val PACKAGE_ID = 0x7F
    const val MAX_ENTRY_ID = 0xFFFE

    /** `ResTable_config.density` for `anydpi`, which beats every density bucket. */
    const val DENSITY_ANY = 0xFFFE

    /** The qualifiers an entry is written for: an `sdkVersion` and a `density`; 0 means unset. */
    data class Config(val sdk: Int, val density: Int = 0)

    /**
     * Beats a base entry in the default configuration, and ties with the `-v29`/`-v31`/`-v34`/`-v35`
     * variants some colors have. minSdk is 23, so aapt2 folds `-v23` resources into the default.
     */
    val DEFAULT_CONFIGS = listOf(Config(23), Config(29), Config(31), Config(34), Config(35))

    /**
     * For drawables: `anydpi` outranks every density bucket a base drawable may live in, and the
     * versions tie with the `-anydpi-v24`, `-v24` and `-v28` variants some have.
     */
    val ANY_DENSITY_CONFIGS = listOf(24, 28, 31, 34, 35).map { Config(it, DENSITY_ANY) }

    sealed class Value {
        data class Color(val argb: Int) : Value()

        /** A style whose items are theme attribute id → color. It replaces the base style wholesale. */
        data class Style(val items: Map<Int, Int>) : Value()

        /** A file the loader's `AssetsProvider` serves; [path] is how resources ask for it. */
        data class File(val path: String) : Value()
    }

    class Entry(
        val id: Int,
        val typeName: String,
        val keyName: String,
        val value: Value,
        val configs: List<Config> = DEFAULT_CONFIGS,
    )

    internal const val RES_STRING_POOL = 0x0001
    internal const val RES_TABLE = 0x0002
    internal const val RES_TABLE_PACKAGE = 0x0200
    internal const val RES_TABLE_TYPE = 0x0201
    internal const val RES_TABLE_TYPE_SPEC = 0x0202
    internal const val STRING_POOL_HEADER = 28
    internal const val PACKAGE_HEADER = 288
    internal const val TYPE_SPEC_HEADER = 16
    internal const val TYPE_HEADER = 84
    internal const val CONFIG_SIZE = 64
    internal const val CONFIG_DENSITY_OFFSET = 14
    internal const val CONFIG_SDK_OFFSET = 24
    internal const val PACKAGE_NAME_CHARS = 128
    internal const val UTF8_FLAG = 0x100
    internal const val SPEC_PUBLIC = 0x40000000
    internal const val FLAG_COMPLEX = 0x0001
    internal const val FLAG_PUBLIC = 0x0002
    internal const val TYPE_STRING = 0x03
    internal const val TYPE_INT_COLOR_ARGB8 = 0x1C
    internal const val NO_ENTRY = -1

    fun write(packageName: String, entries: List<Entry>): ByteArray {
        require(packageName.isNotEmpty() && packageName.length < PACKAGE_NAME_CHARS) { "Bad package name" }
        require(entries.map { it.id }.toSet().size == entries.size) { "Duplicate resource id" }
        entries.forEach {
            require(it.id ushr 24 == PACKAGE_ID) { "Not an app resource: ${Integer.toHexString(it.id)}" }
            require(it.id and 0xFFFF <= MAX_ENTRY_ID) { "Entry id out of range" }
            require(it.keyName.isNotEmpty() && it.typeName.isNotEmpty()) { "Missing resource name" }
            require(it.configs.isNotEmpty() && it.configs.toSet().size == it.configs.size) { "Bad configurations" }
        }
        val sorted = entries.sortedBy { it.id }
        val byType = sorted.groupBy { (it.id ushr 16) and 0xFF }.toSortedMap()
        require(byType.keys.all { it > 0 }) { "Type id 0 is invalid" }
        val typeNames = Array(byType.keys.lastOrNull() ?: 0) { "?${it + 1}" }
        byType.forEach { (typeId, typed) ->
            require(typed.map { it.typeName }.distinct().size == 1) { "Type $typeId has two names" }
            typeNames[typeId - 1] = typed.first().typeName
        }
        val keys = sorted.map { it.keyName }.distinct()
        val keyIndex = keys.withIndex().associate { it.value to it.index }
        val paths = sorted.mapNotNull { (it.value as? Value.File)?.path }.distinct()
        val pathIndex = paths.withIndex().associate { it.value to it.index }

        val typePool = stringPool(typeNames.asList())
        val keyPool = stringPool(keys)
        val packageChunk = chunk(RES_TABLE_PACKAGE, PACKAGE_HEADER) {
            u32(PACKAGE_ID)
            val name = packageName.padEnd(PACKAGE_NAME_CHARS, '\u0000')
            name.forEach { u16(it.code) }
            u32(PACKAGE_HEADER) // typeStrings
            u32(0) // lastPublicType
            u32(PACKAGE_HEADER + typePool.size) // keyStrings
            u32(0) // lastPublicKey
            u32(0) // typeIdOffset
            bytes(typePool)
            bytes(keyPool)
            byType.forEach { (typeId, typed) -> bytes(typeChunks(typeId, typed, keyIndex, pathIndex)) }
        }
        val globalPool = stringPool(paths)
        return chunk(RES_TABLE, 12) {
            u32(1) // packageCount
            bytes(globalPool)
            bytes(packageChunk)
        }
    }

    private fun typeChunks(typeId: Int, entries: List<Entry>, keyIndex: Map<String, Int>, pathIndex: Map<String, Int>): ByteArray {
        val byEntry = entries.associateBy { it.id and 0xFFFF }
        val entryCount = byEntry.keys.max() + 1
        val out = Bytes()
        out.bytes(chunk(RES_TABLE_TYPE_SPEC, TYPE_SPEC_HEADER) {
            u8(typeId).u8(0).u16(0).u32(entryCount)
            for (i in 0 until entryCount) u32(if (i in byEntry) SPEC_PUBLIC else 0)
        })
        val configs = entries.flatMap { it.configs }.distinct().sortedWith(compareBy({ it.density }, { it.sdk }))
        for (config in configs) {
            val body = Bytes()
            val offsets = IntArray(entryCount) { NO_ENTRY }
            for (i in 0 until entryCount) {
                val entry = byEntry[i]?.takeIf { config in it.configs } ?: continue
                offsets[i] = body.size
                val key = keyIndex.getValue(entry.keyName)
                when (val value = entry.value) {
                    is Value.Color -> body.u16(8).u16(FLAG_PUBLIC).u32(key).value(TYPE_INT_COLOR_ARGB8, value.argb)
                    is Value.File -> body.u16(8).u16(FLAG_PUBLIC).u32(key).value(TYPE_STRING, pathIndex.getValue(value.path))
                    is Value.Style -> {
                        body.u16(16).u16(FLAG_COMPLEX or FLAG_PUBLIC).u32(key).u32(0).u32(value.items.size)
                        value.items.toSortedMap().forEach { (attr, argb) -> body.u32(attr).value(TYPE_INT_COLOR_ARGB8, argb) }
                    }
                }
            }
            val entriesBytes = body.toByteArray()
            out.bytes(chunk(RES_TABLE_TYPE, TYPE_HEADER) {
                u8(typeId).u8(0).u16(0).u32(entryCount).u32(TYPE_HEADER + 4 * entryCount)
                val bytes = ByteArray(CONFIG_SIZE)
                bytes[0] = CONFIG_SIZE.toByte()
                bytes[CONFIG_DENSITY_OFFSET] = (config.density and 0xFF).toByte()
                bytes[CONFIG_DENSITY_OFFSET + 1] = (config.density ushr 8).toByte()
                bytes[CONFIG_SDK_OFFSET] = (config.sdk and 0xFF).toByte()
                bytes[CONFIG_SDK_OFFSET + 1] = (config.sdk ushr 8).toByte()
                bytes(bytes)
                offsets.forEach { u32(it) }
                bytes(entriesBytes)
            })
        }
        return out.toByteArray()
    }

    /** A UTF-8 pool; lengths use the one- or two-byte encoding `ResStringPool` expects. */
    private fun stringPool(strings: List<String>): ByteArray {
        val data = Bytes()
        val offsets = strings.map { string ->
            val offset = data.size
            val utf8 = string.toByteArray(Charsets.UTF_8)
            data.length(string.length).length(utf8.size).bytes(utf8).u8(0)
            offset
        }
        data.pad4()
        return chunk(RES_STRING_POOL, STRING_POOL_HEADER) {
            u32(strings.size).u32(0).u32(UTF8_FLAG).u32(STRING_POOL_HEADER + 4 * strings.size).u32(0)
            offsets.forEach { u32(it) }
            bytes(data.toByteArray())
        }
    }

    /** A chunk: the 8-byte `ResChunk_header`, then whatever [fill] writes (the rest of the header, then the body). */
    private fun chunk(type: Int, headerSize: Int, fill: Bytes.() -> Unit): ByteArray {
        val content = Bytes().apply(fill).toByteArray()
        check(content.size % 4 == 0) { "Unaligned chunk" }
        return Bytes().u16(type).u16(headerSize).u32(8 + content.size).bytes(content).toByteArray()
    }

    private class Bytes {
        private val out = ByteArrayOutputStream()
        val size get() = out.size()
        fun u8(value: Int) = apply { out.write(value and 0xFF) }
        fun u16(value: Int) = apply { u8(value).u8(value ushr 8) }
        fun u32(value: Int) = apply { u16(value).u16(value ushr 16) }
        fun bytes(value: ByteArray) = apply { out.write(value) }
        fun pad4() = apply { while (out.size() % 4 != 0) out.write(0) }
        fun value(type: Int, data: Int) = u16(8).u8(0).u8(type).u32(data)
        fun length(value: Int) = apply {
            require(value <= 0x7FFF) { "String too long" }
            if (value > 0x7F) u8(0x80 or (value ushr 8)).u8(value) else u8(value)
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
