package com.discord.utilities.theme

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compiled (binary) XML as aapt2 writes it into an APK, just enough to recolor a drawable.
 *
 * Aliucord tints a drawable after it is loaded. Without hooks, the drawable resource itself has to
 * change, so a theme's table points the drawable at a copy of its compiled XML with `android:tint`
 * added ([tint]). PNG drawables get a generated `<bitmap>` wrapper instead ([bitmap]).
 *
 * Only attributes are added and only strings are appended. Every string index already in the file
 * keeps its meaning, so the nodes this does not touch are copied byte for byte.
 */
internal class BinaryXml private constructor(
    private val strings: MutableList<String>,
    private val utf8: Boolean,
    private val resIds: MutableList<Int>,
    private val nodes: List<Node>,
) {
    /** The framework attribute ids this needs; real ones on a device, any ascending ids in tests. */
    class Ids(val drawable: Int, val src: Int, val tint: Int, val tintMode: Int)

    private sealed class Node {
        class Raw(val bytes: ByteArray) : Node()

        class Element(
            val head: ByteArray,
            val ns: Int,
            val name: Int,
            val attrs: MutableList<Attr>,
            var idIndex: Int,
            var classIndex: Int,
            var styleIndex: Int,
        ) : Node()
    }

    private class Attr(val ns: Int, val name: Int, var raw: Int, var type: Int, var data: Int)

    private fun nameOf(element: Node.Element) = strings.getOrNull(element.name)
    private fun resIdOf(attr: Attr) = resIds.getOrNull(attr.name) ?: 0

    /** A string index that maps to [resId], appending [name] when the file has none. */
    private fun attrName(resId: Int, name: String): Int {
        val existing = resIds.indexOf(resId)
        if (existing >= 0) return existing
        val index = strings.size
        strings += name
        while (resIds.size < index) resIds += 0
        resIds += resId
        return index
    }

    private fun stringIndex(value: String): Int =
        strings.indexOf(value).takeIf { it >= 0 } ?: strings.size.also { strings += value }

    private fun setAttr(element: Node.Element, resId: Int, name: String, type: Int, data: Int) {
        element.attrs.firstOrNull { resIdOf(it) == resId }?.let {
            it.raw = NO_STRING
            it.type = type
            it.data = data
            return
        }
        val attr = Attr(stringIndex(ANDROID_NS), attrName(resId, name), NO_STRING, type, data)
        // aapt2 sorts attributes by resource id, and the framework's attribute lookup relies on it.
        val position = element.attrs.indexOfFirst { resIdOf(it) == 0 || Integer.compareUnsigned(resIdOf(it), resId) > 0 }
            .takeIf { it >= 0 } ?: element.attrs.size
        element.attrs.add(position, attr)
        // The id/class/style indices are 1-based positions into the attributes.
        if (element.idIndex > position) element.idIndex++
        if (element.classIndex > position) element.classIndex++
        if (element.styleIndex > position) element.styleIndex++
    }

    private fun toByteArray(): ByteArray {
        val body = Bytes()
        body.bytes(stringPool())
        body.bytes(Bytes().u16(RES_XML_RESOURCE_MAP).u16(8).u32(8 + 4 * resIds.size).apply { resIds.forEach { u32(it) } }.toByteArray())
        nodes.forEach { node ->
            when (node) {
                is Node.Raw -> body.bytes(node.bytes)
                is Node.Element -> {
                    val size = node.head.size + EXT_SIZE + ATTR_SIZE * node.attrs.size
                    val head = node.head.copyOf()
                    ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).putInt(4, size)
                    body.bytes(head).u32(node.ns).u32(node.name)
                        .u16(EXT_SIZE).u16(ATTR_SIZE).u16(node.attrs.size)
                        .u16(node.idIndex).u16(node.classIndex).u16(node.styleIndex)
                    node.attrs.forEach { body.u32(it.ns).u32(it.name).u32(it.raw).u16(8).u8(0).u8(it.type).u32(it.data) }
                }
            }
        }
        val content = body.toByteArray()
        return Bytes().u16(RES_XML).u16(8).u32(8 + content.size).bytes(content).toByteArray()
    }

    private fun stringPool(): ByteArray {
        val data = Bytes()
        val offsets = strings.map { string ->
            val offset = data.size
            if (utf8) {
                val bytes = string.toByteArray(Charsets.UTF_8)
                data.utf8Length(string.length).utf8Length(bytes.size).bytes(bytes).u8(0)
            } else {
                require(string.length <= 0x7FFF) { "String too long" }
                data.u16(string.length)
                string.forEach { data.u16(it.code) }
                data.u16(0)
            }
            offset
        }
        data.pad4()
        // Appending strings breaks any sort order, so the sorted flag is dropped.
        val flags = if (utf8) UTF8_FLAG else 0
        return Bytes().u16(RES_STRING_POOL).u16(POOL_HEADER).u32(POOL_HEADER + 4 * strings.size + data.size)
            .u32(strings.size).u32(0).u32(flags).u32(POOL_HEADER + 4 * strings.size).u32(0)
            .apply { offsets.forEach { u32(it) } }
            .bytes(data.toByteArray()).toByteArray()
    }

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val RES_STRING_POOL = 0x0001
        private const val RES_XML = 0x0003
        private const val RES_XML_START_NAMESPACE = 0x0100
        private const val RES_XML_END_NAMESPACE = 0x0101
        private const val RES_XML_START_ELEMENT = 0x0102
        private const val RES_XML_RESOURCE_MAP = 0x0180
        private const val POOL_HEADER = 28
        private const val NODE_HEADER = 16
        private const val EXT_SIZE = 20
        private const val ATTR_SIZE = 20
        private const val UTF8_FLAG = 0x100
        private const val NO_STRING = -1
        private const val TYPE_REFERENCE = 0x01
        private const val TYPE_INT_DEC = 0x10
        private const val TYPE_INT_COLOR_ARGB8 = 0x1C
        private const val TINT_MODE_SRC_IN = 5

        /** Elements whose drawable class reads `android:tint` from XML. */
        private val TINTABLE = setOf("vector", "shape", "bitmap", "nine-patch")

        /**
         * [xml] with every tintable element tinted [color]. Child drawables it references through
         * `android:drawable` are swapped for [redirect]'s result, which is how a selector's or
         * layer list's referenced items get tinted too.
         *
         * `null` when nothing in the file could be tinted. Throws [IllegalArgumentException] for
         * XML it cannot parse.
         */
        fun tint(xml: ByteArray, color: Int, ids: Ids, redirect: (Int) -> Int?): ByteArray? {
            val doc = parse(xml)
            var changed = false
            doc.nodes.filterIsInstance<Node.Element>().forEach { element ->
                if (doc.nameOf(element) in TINTABLE) {
                    doc.setAttr(element, ids.tint, "tint", TYPE_INT_COLOR_ARGB8, color)
                    doc.setAttr(element, ids.tintMode, "tintMode", TYPE_INT_DEC, TINT_MODE_SRC_IN)
                    changed = true
                }
                element.attrs.forEach { attr ->
                    if (attr.type == TYPE_REFERENCE && doc.resIdOf(attr) == ids.drawable) {
                        redirect(attr.data)?.let {
                            attr.data = it
                            changed = true
                        }
                    }
                }
            }
            return if (changed) doc.toByteArray() else null
        }

        /** A `<bitmap>` (or `<nine-patch>`) showing drawable [srcId], tinted [color]. */
        fun bitmap(srcId: Int, ninePatch: Boolean, color: Int, ids: Ids): ByteArray {
            val strings = mutableListOf("src", "tint", "tintMode", "android", ANDROID_NS, if (ninePatch) "nine-patch" else "bitmap")
            val resIds = mutableListOf(ids.src, ids.tint, ids.tintMode)
            val attrs = mutableListOf(
                Attr(4, 0, NO_STRING, TYPE_REFERENCE, srcId),
                Attr(4, 1, NO_STRING, TYPE_INT_COLOR_ARGB8, color),
                Attr(4, 2, NO_STRING, TYPE_INT_DEC, TINT_MODE_SRC_IN),
            ).sortedWith { a, b -> Integer.compareUnsigned(resIds[a.name], resIds[b.name]) }.toMutableList()
            fun node(type: Int, ext: Bytes.() -> Unit): ByteArray {
                val body = Bytes().apply(ext).toByteArray()
                return Bytes().u16(type).u16(NODE_HEADER).u32(NODE_HEADER + body.size).u32(1).u32(NO_STRING).bytes(body).toByteArray()
            }
            val elementHead = Bytes().u16(RES_XML_START_ELEMENT).u16(NODE_HEADER).u32(0).u32(1).u32(NO_STRING).toByteArray()
            val nodes = listOf(
                Node.Raw(node(RES_XML_START_NAMESPACE) { u32(3).u32(4) }),
                Node.Element(elementHead, NO_STRING, 5, attrs, 0, 0, 0),
                Node.Raw(node(RES_XML_END_ELEMENT) { u32(NO_STRING).u32(5) }),
                Node.Raw(node(RES_XML_END_NAMESPACE) { u32(3).u32(4) }),
            )
            return BinaryXml(strings, utf8 = false, resIds = resIds, nodes = nodes).toByteArray()
        }

        private const val RES_XML_END_ELEMENT = 0x0103

        /** Element name and its attributes as resource id → (type, data), for tests. */
        internal fun elements(xml: ByteArray): List<Pair<String, Map<Int, Pair<Int, Int>>>> {
            val doc = parse(xml)
            return doc.nodes.filterIsInstance<Node.Element>().map { element ->
                doc.nameOf(element).orEmpty() to element.attrs.associate { doc.resIdOf(it) to (it.type to it.data) }
            }
        }

        private fun parse(bytes: ByteArray): BinaryXml {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            try {
                require(buffer.getShort(0).toInt() == RES_XML && buffer.getShort(2).toInt() == 8) { "Not compiled XML" }
                require(buffer.getInt(4) == bytes.size) { "Size mismatch" }
                var strings: MutableList<String>? = null
                var utf8 = false
                val resIds = mutableListOf<Int>()
                val nodes = mutableListOf<Node>()
                var offset = 8
                while (offset < bytes.size) {
                    val type = buffer.getShort(offset).toInt() and 0xFFFF
                    val size = buffer.getInt(offset + 4)
                    require(size >= 8 && offset + size <= bytes.size) { "Bad chunk" }
                    when (type) {
                        RES_STRING_POOL -> {
                            utf8 = buffer.getInt(offset + 16) and UTF8_FLAG != 0
                            strings = readStrings(buffer, offset, utf8)
                        }
                        RES_XML_RESOURCE_MAP -> {
                            val headerSize = buffer.getShort(offset + 2).toInt()
                            for (i in 0 until (size - headerSize) / 4) resIds += buffer.getInt(offset + headerSize + 4 * i)
                        }
                        RES_XML_START_ELEMENT -> nodes += readElement(buffer, offset, size)
                        else -> nodes += Node.Raw(bytes.copyOfRange(offset, offset + size))
                    }
                    offset += size
                }
                return BinaryXml(requireNotNull(strings) { "No string pool" }, utf8, resIds, nodes)
            } catch (e: IndexOutOfBoundsException) {
                throw IllegalArgumentException("Truncated XML", e)
            }
        }

        private fun readElement(buffer: ByteBuffer, offset: Int, size: Int): Node.Element {
            val headerSize = buffer.getShort(offset + 2).toInt()
            val head = ByteArray(headerSize).also { for (i in it.indices) it[i] = buffer.get(offset + i) }
            val ext = offset + headerSize
            val attrStart = buffer.getShort(ext + 8).toInt()
            val attrSize = buffer.getShort(ext + 10).toInt()
            val count = buffer.getShort(ext + 12).toInt() and 0xFFFF
            require(attrSize == ATTR_SIZE && attrStart >= EXT_SIZE && ext + attrStart + count * attrSize <= offset + size) {
                "Unsupported attribute layout"
            }
            val attrs = (0 until count).mapTo(mutableListOf()) { i ->
                val at = ext + attrStart + i * attrSize
                Attr(buffer.getInt(at), buffer.getInt(at + 4), buffer.getInt(at + 8), buffer.get(at + 15).toInt() and 0xFF, buffer.getInt(at + 16))
            }
            return Node.Element(
                head = head,
                ns = buffer.getInt(ext),
                name = buffer.getInt(ext + 4),
                attrs = attrs,
                idIndex = buffer.getShort(ext + 14).toInt() and 0xFFFF,
                classIndex = buffer.getShort(ext + 16).toInt() and 0xFFFF,
                styleIndex = buffer.getShort(ext + 18).toInt() and 0xFFFF,
            )
        }

        private fun readStrings(buffer: ByteBuffer, pool: Int, utf8: Boolean): MutableList<String> {
            val count = buffer.getInt(pool + 8)
            require(buffer.getInt(pool + 12) == 0) { "Styled strings are not supported" }
            val stringsStart = pool + buffer.getInt(pool + 20)
            val headerSize = buffer.getShort(pool + 2).toInt()
            return (0 until count).mapTo(mutableListOf()) { i ->
                var at = stringsStart + buffer.getInt(pool + headerSize + 4 * i)
                if (utf8) {
                    fun length(): Int {
                        val first = buffer.get(at++).toInt() and 0xFF
                        return if (first and 0x80 != 0) ((first and 0x7F) shl 8) or (buffer.get(at++).toInt() and 0xFF) else first
                    }
                    length()
                    val byteLength = length()
                    String(ByteArray(byteLength) { buffer.get(at + it) }, Charsets.UTF_8)
                } else {
                    var length = buffer.getShort(at).toInt() and 0xFFFF
                    at += 2
                    if (length and 0x8000 != 0) {
                        length = ((length and 0x7FFF) shl 16) or (buffer.getShort(at).toInt() and 0xFFFF)
                        at += 2
                    }
                    String(CharArray(length) { buffer.getChar(at + 2 * it) })
                }
            }
        }
    }

    private class Bytes {
        private val out = ByteArrayOutputStream()
        val size get() = out.size()
        fun u8(value: Int) = apply { out.write(value and 0xFF) }
        fun u16(value: Int) = apply { u8(value).u8(value ushr 8) }
        fun u32(value: Int) = apply { u16(value).u16(value ushr 16) }
        fun bytes(value: ByteArray) = apply { out.write(value) }
        fun pad4() = apply { while (out.size() % 4 != 0) out.write(0) }
        fun utf8Length(value: Int) = apply {
            require(value <= 0x7FFF) { "String too long" }
            if (value > 0x7F) u8(0x80 or (value ushr 8)).u8(value) else u8(value)
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
