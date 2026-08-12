package com.discord.utilities.persister

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Output
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import rx.Observable

class PersisterTest {
    private val directory = Files.createTempDirectory("persister-test").toFile()

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun survivesRestartFallsBackFromCorruptionAndResets() {
        Persister.init(directory, Observable.never())
        Persister("setting", arrayListOf<String>()).set(arrayListOf("one", "two"), persist = true)

        Persister.init(directory, Observable.never())
        assertEquals(listOf("one", "two"), Persister("setting", arrayListOf<String>()).get())

        directory.resolve("setting").copyTo(directory.resolve("setting.bak"))
        directory.resolve("setting").writeText("not kryo")
        Persister.init(directory, Observable.never())
        assertEquals(listOf("one", "two"), Persister("setting", arrayListOf<String>()).get())

        directory.resolve("setting").writeText("not kryo")
        Persister.init(directory, Observable.never())
        assertEquals(emptyList<String>(), Persister("setting", arrayListOf<String>()).get())

        Persister("other", false).set(true, persist = true)
        Persister.reset()
        Persister.init(directory, Observable.never())
        assertEquals(false, Persister("other", false).get())
    }

    @Test
    fun rejectsKeysOutsidePrivateFilesDirectory() {
        Persister.init(directory, Observable.never())
        assertThrows(IllegalArgumentException::class.java) { Persister("../secret", false) }
    }

    @Test
    fun invalidatesLegacyCacheFormat() {
        val file = directory.resolve("legacy")
        Output(file.outputStream()).use { Kryo().writeClassAndObject(it, arrayListOf("old")) }

        Persister.init(directory, Observable.never())

        assertEquals(emptyList<String>(), Persister("legacy", arrayListOf<String>()).get())
        assertFalse(file.exists())
    }
}
