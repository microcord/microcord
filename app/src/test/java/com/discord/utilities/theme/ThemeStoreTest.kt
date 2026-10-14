package com.discord.utilities.theme

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStoreTest {
    private val root = Files.createTempDirectory("themes").toFile()
    private val dir = File(root, "themes")
    private val store = ThemeStore(dir)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun savesOverwritesListsAndDeletes() {
        val id = store.save(theme("Zeta"))
        store.save(theme("Alpha"))
        store.save(theme("Zeta 2"), id)
        assertEquals(listOf("Alpha", "Zeta 2"), store.list().map { it.theme.manifest.name })
        val other = store.list().first { it.id != id }.id
        store.activeIds = listOf(id, other)
        assertEquals(listOf(id, other), store.activeIds)
        store.delete(id)
        assertEquals(listOf(other), store.activeIds)
        assertEquals(listOf("Alpha"), store.list().map { it.theme.manifest.name })
        assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun unreadableFilesAndForeignNamesAreIgnored() {
        dir.mkdirs()
        File(dir, "t-000000000000.json").writeText("{broken")
        File(root, "escape.json").writeText("""{"colors":{}}""")
        File(dir, "notes.json").writeText("""{"colors":{}}""")
        assertTrue(store.list().isEmpty())
        assertNull(store.read("../escape"))
        assertThrows(IllegalArgumentException::class.java) { store.save(theme("x"), "../escape") }
        File(dir, "active").writeText("t-ffffffffffff")
        assertEquals("a dangling active id reads as built-in", emptyList<String>(), store.activeIds)
    }

    @Test
    fun bootGuardMarkers() {
        assertEquals(0, store.unconfirmedLaunches)
        store.unconfirmedLaunches = 2
        assertEquals(2, ThemeStore(dir).unconfirmedLaunches)
        store.unconfirmedLaunches = 0
        assertEquals(0, store.unconfirmedLaunches)
        assertFalse(store.consumeDisabledByGuard())
        store.markDisabledByGuard()
        assertTrue(store.consumeDisabledByGuard())
        assertFalse(store.consumeDisabledByGuard())
    }

    @Test
    fun generatedIdsAreValid() {
        repeat(20) { assertTrue(ThemeStore.isValidId(ThemeStore.newId())) }
        listOf("", "t-", "t-ABCDEF012345", "x-0123456789ab", "t-0123456789abc")
            .forEach { assertFalse(it, ThemeStore.isValidId(it)) }
    }

    private fun theme(name: String) = ThemeDefinition(ThemeDefinition.Manifest(name), colors = mapOf("brand_new" to -1))
}
