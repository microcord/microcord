package com.discord.utilities.theme

import com.discord.utilities.theme.ArscWriter.Entry
import com.discord.utilities.theme.ArscWriter.Value
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArscWriterTest {
    private val pkg = "dev.mantikafasi.manticord"
    private val entries = listOf(
        Entry(0x7F060012, "color", "brand_new", Value.Color(0x805865F2.toInt())),
        Entry(0x7F060003, "color", "primary_600", Value.Color(-1)),
        Entry(0x7F140301, "style", "ThemeOverlay.Microcord.Runtime", Value.Style(mapOf(0x7F040020 to 2, 0x01010031 to 1, 0x7F040001 to 3))),
    )

    @Test
    fun roundTripsThroughTheReader() {
        val table = ArscReader.read(ArscWriter.write(pkg, entries))
        assertEquals(0x7F, table.packageId)
        assertEquals(pkg, table.packageName)
        assertEquals(setOf(0x06, 0x14), table.types.keys)
        val colors = table.types.getValue(0x06)
        assertEquals("color", colors.name)
        assertEquals(ArscWriter.DEFAULT_CONFIGS, colors.configs.keys.toList())
        colors.configs.values.forEach { config ->
            assertEquals(setOf(0x03, 0x12), config.keys)
            assertEquals("brand_new", config.getValue(0x12).key)
            assertEquals(Value.Color(0x805865F2.toInt()), config.getValue(0x12).value)
            assertEquals(Value.Color(-1), config.getValue(0x03).value)
        }
        val style = table.types.getValue(0x14).configs.getValue(ArscWriter.Config(23)).getValue(0x301)
        assertEquals("ThemeOverlay.Microcord.Runtime", style.key)
        // Bag items are sorted by unsigned attribute id: framework attributes first.
        assertEquals(listOf(0x01010031, 0x7F040001, 0x7F040020), (style.value as Value.Style).items.keys.toList())
    }

    @Test
    fun layoutMatchesResourceTypesH() {
        val bytes = ArscWriter.write(pkg, entries.take(1))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0002, buffer.getShort(0).toInt())
        assertEquals(12, buffer.getShort(2).toInt())
        assertEquals(bytes.size, buffer.getInt(4))
        assertEquals(0, bytes.size % 4)
        // The empty global pool is a bare 28-byte header; the package header follows at 40.
        assertEquals(28, buffer.getInt(12 + 4))
        assertEquals(0x0200, buffer.getShort(40).toInt())
        assertEquals(288, buffer.getShort(42).toInt())
        // One color type at id 6: five placeholder names, then "color".
        val types = ArscReader.read(bytes).types
        assertEquals("color", types.getValue(6).name)
        // Header, empty pool, package header, pools, spec (16 + 4 * 19) and four type chunks (84 + 4 * 19 + 16).
        assertTrue(bytes.size > 40 + 288 + 16 + 4 * 19 + 4 * (84 + 4 * 19 + 16))
    }

    @Test
    fun filesAndPerEntryConfigurations() {
        val table = ArscReader.read(
            ArscWriter.write(
                pkg,
                listOf(
                    Entry(0x7F080010, "drawable", "ic_a", Value.File("res/microcord/a.xml"), ArscWriter.ANY_DENSITY_CONFIGS),
                    Entry(0x7F080011, "drawable", "src_b", Value.File("res/microcord/b.png"), listOf(ArscWriter.Config(0, 480))),
                    Entry(0x7F0A0002, "font", "whitney_medium", Value.File("res/microcord/f.ttf")),
                ),
            ),
        )
        val drawables = table.types.getValue(0x08).configs
        assertEquals(ArscWriter.ANY_DENSITY_CONFIGS.toSet() + ArscWriter.Config(0, 480), drawables.keys)
        assertEquals(Value.File("res/microcord/a.xml"), drawables.getValue(ArscWriter.Config(24, ArscWriter.DENSITY_ANY)).getValue(0x10).value)
        assertEquals(setOf(0x11), drawables.getValue(ArscWriter.Config(0, 480)).keys)
        assertEquals(Value.File("res/microcord/f.ttf"), table.types.getValue(0x0A).configs.getValue(ArscWriter.Config(23)).getValue(2).value)
    }

    @Test
    fun writingIsDeterministic() {
        assertArrayEquals(ArscWriter.write(pkg, entries), ArscWriter.write(pkg, entries.reversed()))
    }

    @Test
    fun longNamesUseTheTwoByteLengthForm() {
        val key = "k".repeat(300)
        val table = ArscReader.read(ArscWriter.write(pkg, listOf(Entry(0x7F060001, "color", key, Value.Color(0)))))
        assertEquals(key, table.types.getValue(6).configs.getValue(ArscWriter.Config(35)).getValue(1).key)
    }

    @Test
    fun rejectsWhatTheFrameworkWould() {
        val color = Value.Color(0)
        listOf(
            listOf(Entry(0x01060001, "color", "a", color)),
            listOf(Entry(0x7F060001, "color", "a", color), Entry(0x7F060001, "color", "b", color)),
            listOf(Entry(0x7F06FFFF, "color", "a", color)),
            listOf(Entry(0x7F060001, "color", "a", color), Entry(0x7F060002, "colour", "b", color)),
            listOf(Entry(0x7F000001, "color", "a", color)),
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { ArscWriter.write(pkg, bad) } }
        assertThrows(IllegalArgumentException::class.java) { ArscWriter.write("x".repeat(128), entries) }
    }

    @Test
    fun readerRejectsCorruption() {
        val good = ArscWriter.write(pkg, entries)
        assertThrows(IllegalArgumentException::class.java) { ArscReader.read(good.copyOf(good.size - 4)) }
        assertThrows(IllegalArgumentException::class.java) { ArscReader.read(good + ByteArray(4)) }
        val wrongHeader = good.copyOf().also { it[42] = 0 }
        assertThrows(IllegalArgumentException::class.java) { ArscReader.read(wrongHeader) }
    }
}
