package com.discord.utilities.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are drawables exactly as aapt2 compiled them into this app's APK. */
class BinaryXmlTest {
    private val ids = BinaryXml.Ids(drawable = 0x01010199, src = 0x01010119, tint = 0x01010121, tintMode = 0x010103fb)
    private val color = 0xFF112233.toInt()

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/theme/$name")).readBytes()

    @Test
    fun vectorGetsATintInAttributeOrder() {
        val tinted = requireNotNull(BinaryXml.tint(fixture("ic_channel_text.xml"), color, ids) { null })
        val (name, attrs) = BinaryXml.elements(tinted).first()
        assertEquals("vector", name)
        assertEquals(0x1C to color, attrs[ids.tint])
        assertEquals(0x10 to 5, attrs[ids.tintMode])
        val order = attrs.keys.toList()
        assertEquals(order.sortedWith { a, b -> Integer.compareUnsigned(a, b) }, order)
        // Everything else survives untouched.
        assertEquals(0x1D to 0xFF72767D.toInt(), BinaryXml.elements(tinted)[1].second[0x01010404])
    }

    @Test
    fun shapesAreTintedAndTintingTwiceReplaces() {
        val once = requireNotNull(BinaryXml.tint(fixture("drawable_button_grey.xml"), color, ids) { null })
        val twice = requireNotNull(BinaryXml.tint(once, -1, ids) { null })
        val shape = BinaryXml.elements(twice).first()
        assertEquals("shape", shape.first)
        assertEquals(0x1C to -1, shape.second[ids.tint])
        assertEquals(2, shape.second.size)
    }

    @Test
    fun selectorsRedirectTheirItems() {
        val tinted = requireNotNull(BinaryXml.tint(fixture("drawable_overlay_channels_selected_dark.xml"), color, ids) { it + 1 })
        val items = BinaryXml.elements(tinted).filter { it.first == "item" }
        assertEquals(listOf(0x7f0801f5, 0x7f0801f5, 0x7f0801f7), items.map { it.second.getValue(ids.drawable).second })
        assertNull("a selector with nothing to redirect has nothing to tint", BinaryXml.tint(fixture("drawable_overlay_channels_selected_dark.xml"), color, ids) { null })
    }

    @Test
    fun bitmapWrapper() {
        val xml = BinaryXml.bitmap(0x7f080123, ninePatch = false, color = color, ids = ids)
        val (name, attrs) = BinaryXml.elements(xml).single()
        assertEquals("bitmap", name)
        assertEquals(listOf(ids.src, ids.tint, ids.tintMode), attrs.keys.toList())
        assertEquals(0x01 to 0x7f080123, attrs[ids.src])
        assertNotNull(BinaryXml.tint(xml, -1, ids) { null })
    }

    @Test
    fun rejectsWhatIsNotCompiledXml() {
        assertThrows(IllegalArgumentException::class.java) { BinaryXml.tint("<vector/>".toByteArray(), color, ids) { null } }
        val truncated = fixture("ic_channel_text.xml").let { it.copyOf(it.size - 8) }
        assertThrows(IllegalArgumentException::class.java) { BinaryXml.tint(truncated, color, ids) { null } }
        assertTrue(fixture("ic_channel_text.xml").isNotEmpty())
    }
}
