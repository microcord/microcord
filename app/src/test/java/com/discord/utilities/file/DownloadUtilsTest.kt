package com.discord.utilities.file

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUtilsTest {
    @Test
    fun `download target is sanitized and confined`() {
        val directory = Files.createTempDirectory("download-utils").toFile()
        try {
            val target = DownloadUtils.resolveDownloadFile(directory, "../shared_prefs/auth?.xml")

            assertEquals("auth_.xml", target.name)
            assertEquals(directory.canonicalFile, target.parentFile)
            assertTrue(target.path.startsWith(directory.canonicalPath))
        } finally {
            directory.deleteRecursively()
        }
    }
}
