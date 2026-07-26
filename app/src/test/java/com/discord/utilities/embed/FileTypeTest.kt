package com.discord.utilities.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The declaration order of [FileType] is the matching order, and several extensions match more than
 * one pattern. These pin the overlaps — reordering the enum breaks them.
 */
class FileTypeTest {

    @Test
    fun `js is code before it is web code`() {
        assertEquals(FileType.CODE, FileType.getFromExtension("js"))
    }

    @Test
    fun `xls is a spreadsheet before it is web code`() {
        assertEquals(FileType.SPREADSHEET, FileType.getFromExtension("xls"))
    }

    @Test
    fun `patterns are anchored at the end, so a whole filename resolves`() {
        assertEquals(FileType.ARCHIVE, FileType.getFromExtension("backup.tar.gz"))
        assertEquals(FileType.VIDEO, FileType.getFromExtension("holiday.mp4"))
    }

    @Test
    fun `an unknown extension has no type`() {
        assertNull(FileType.getFromExtension("wumpus"))
        assertNull(FileType.getFromExtension(""))
    }

    @Test
    fun `matching is case sensitive, as in the reference`() {
        assertNull(FileType.getFromExtension("PDF"))
        assertEquals(FileType.ACROBAT, FileType.getFromExtension("pdf"))
    }

    @Test
    fun `the matcher is reset between calls`() {
        repeat(3) {
            assertEquals(FileType.DOCUMENT, FileType.getFromExtension("notes.txt"))
        }
    }
}
