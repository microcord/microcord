package com.discord.widgets.guilds.list

import com.discord.models.domain.ModelGuildFolder
import com.discord.models.guild.Guild
import com.discord.stores.StoreGuildsSorted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GuildFolderReorderTest {
    @Test fun movesSingletonsBeforeAndAfter() {
        assertEquals(listOf(3L, 1L, 2L), ids(GuildFolderReorder.moveBefore(singletons(1, 2, 3), guild(3), guild(1))))
        assertEquals(listOf(2L, 1L, 3L), ids(GuildFolderReorder.moveAfter(singletons(1, 2, 3), guild(1), guild(2))))
    }

    @Test fun reordersFoldersIncludingOpenFolders() {
        val entries = listOf(folder(10, 1, 2), singleton(3), folder(20, 4, 5))
        assertEquals(listOf(20L, 10L), folderIds(GuildFolderReorder.moveBefore(entries, folderKey(20), folderKey(10))))
    }

    @Test fun movesGuildsWithinAndBetweenFoldersAndRemovesEmptyFolders() {
        val within = GuildFolderReorder.moveBefore(listOf(folder(10, 1, 2, 3)), guild(3), guild(1))
        assertEquals(listOf(3L, 1L, 2L), ids(within))

        val between = GuildFolderReorder.moveAfter(listOf(folder(10, 1), folder(20, 2, 3)), guild(1), guild(2))
        assertEquals(listOf(2L, 1L, 3L), ids(between))
        assertEquals(listOf(20L), folderIds(between))
    }

    @Test fun createsFolderWithTargetFirstAndCallerGeneratedNegativeId() {
        var generated = 0
        val result = GuildFolderReorder.createFolder(singletons(1, 2, 3), 1, 2, { generated++; -42 })
        assertEquals(1, generated)
        assertEquals(listOf(2L, 1L, 3L), ids(result))
        assertEquals(listOf(-42L), folderIds(result))
    }

    @Test fun rejectsCollidingReservedAndNonNegativeTemporaryIds() {
        val entries = singletons(1, 2)
        assertThrows(IllegalArgumentException::class.java) {
            GuildFolderReorder.createFolder(entries, 1, 2, { -7 }, reservedFolderIds = setOf(-7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuildFolderReorder.createFolder(entries, 1, 2, { 1 })
        }
    }

    @Test fun addsOnlyToClosedFolder() {
        val entries = listOf(singleton(1), folder(10, 2))
        assertEquals(listOf(2L, 1L), ids(GuildFolderReorder.addGuildToFolder(entries, 1, 10)))
        assertSame(entries, GuildFolderReorder.addGuildToFolder(entries, 1, 10, openFolderIds = setOf(10)))
    }

    @Test fun removesGuildAfterFolderAndPreservesOneChildFolder() {
        val result = GuildFolderReorder.removeGuildFromFolder(listOf(folder(10, 1, 2), singleton(3)), 1)
        assertEquals(listOf(2L, 1L, 3L), ids(result))
        assertEquals(listOf(10L), folderIds(result))
        assertEquals(listOf(2L), (result.first() as StoreGuildsSorted.Entry.Folder).guilds.map(Guild::id))
    }

    @Test fun removingOnlyChildDissolvesEmptyFolder() {
        val result = GuildFolderReorder.removeGuildFromFolder(listOf(folder(10, 1), singleton(2)), 1)
        assertEquals(listOf(1L, 2L), ids(result))
        assertEquals(emptyList<Long>(), folderIds(result))
    }

    @Test fun rejectsDuplicateAndExcludedGuilds() {
        assertThrows(IllegalArgumentException::class.java) {
            GuildFolderReorder.moveAfter(listOf(singleton(1), folder(10, 1)), guild(1), folderKey(10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuildFolderReorder.moveAfter(singletons(1, 2), guild(1), guild(2), excludedGuildIds = setOf(1))
        }
    }

    @Test fun persistenceRequestKeepsEntryAndGuildOrder() {
        val entries = listOf(singleton(1), folder(10, 3, 2), singleton(4))
        var persisted: List<ModelGuildFolder>? = null
        GuildFolderPersistenceRequest { persisted = it }.submit(entries)
        assertEquals(entries.map(StoreGuildsSorted.Entry::asModelGuildFolder), persisted)
        assertEquals(listOf(listOf(1L), listOf(3L, 2L), listOf(4L)), persisted!!.map(ModelGuildFolder::guildIds))
    }

    private fun guild(id: Long) = GuildFolderReorder.EntryKey.Guild(id)
    private fun folderKey(id: Long) = GuildFolderReorder.EntryKey.Folder(id)
    private fun singleton(id: Long) = StoreGuildsSorted.Entry.SingletonGuild(Guild(id = id, name = "Guild $id"))
    private fun singletons(vararg ids: Long) = ids.map(::singleton)
    private fun folder(id: Long, vararg guildIds: Long) = StoreGuildsSorted.Entry.Folder(
        id, guildIds.map { Guild(id = it, name = "Guild $it") }, color = id.toInt(), name = "Folder $id",
    )
    private fun ids(entries: List<StoreGuildsSorted.Entry>) = entries.flatMap { it.asModelGuildFolder().guildIds }
    private fun folderIds(entries: List<StoreGuildsSorted.Entry>) = entries.filterIsInstance<StoreGuildsSorted.Entry.Folder>().map { it.id }
}
