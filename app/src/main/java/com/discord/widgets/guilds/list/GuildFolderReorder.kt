package com.discord.widgets.guilds.list

import com.discord.models.guild.Guild
import com.discord.stores.StoreGuildsSorted

object GuildFolderReorder {
    sealed class EntryKey {
        data class Guild(val id: Long) : EntryKey()
        data class Folder(val id: Long) : EntryKey()
    }

    sealed class Operation {
        data class MoveBefore(val source: EntryKey, val target: EntryKey) : Operation()
        data class MoveAfter(val source: EntryKey, val target: EntryKey) : Operation()
    }

    fun moveBefore(
        entries: List<StoreGuildsSorted.Entry>,
        source: EntryKey,
        target: EntryKey,
        excludedGuildIds: Set<Long> = emptySet(),
    ) = move(entries, source, target, after = false, excludedGuildIds = excludedGuildIds)

    fun moveAfter(
        entries: List<StoreGuildsSorted.Entry>,
        source: EntryKey,
        target: EntryKey,
        excludedGuildIds: Set<Long> = emptySet(),
    ) = move(entries, source, target, after = true, excludedGuildIds = excludedGuildIds)

    fun apply(
        entries: List<StoreGuildsSorted.Entry>,
        operation: Operation,
        excludedGuildIds: Set<Long> = emptySet(),
    ): List<StoreGuildsSorted.Entry> = when (operation) {
        is Operation.MoveBefore -> moveBefore(entries, operation.source, operation.target, excludedGuildIds)
        is Operation.MoveAfter -> moveAfter(entries, operation.source, operation.target, excludedGuildIds)
    }

    fun createFolder(
        entries: List<StoreGuildsSorted.Entry>,
        draggedGuildId: Long,
        targetGuildId: Long,
        nextFolderId: () -> Long,
        reservedFolderIds: Set<Long> = emptySet(),
        excludedGuildIds: Set<Long> = emptySet(),
    ): List<StoreGuildsSorted.Entry> {
        validate(entries, excludedGuildIds)
        if (draggedGuildId == targetGuildId) return entries
        val dragged = entries.singleton(draggedGuildId) ?: return entries
        val target = entries.singleton(targetGuildId) ?: return entries
        val folderId = nextFolderId()
        require(folderId < 0 && folderId !in reservedFolderIds && entries.none { it is StoreGuildsSorted.Entry.Folder && it.id == folderId }) {
            "Temporary folder ID must be negative, unique, and not reserved: $folderId"
        }
        return entries.mapNotNull { entry ->
            when {
                entry === dragged -> null
                entry === target -> StoreGuildsSorted.Entry.Folder(folderId, listOf(target.guild, dragged.guild), null, null)
                else -> entry
            }
        }.validated(excludedGuildIds)
    }

    fun addGuildToFolder(
        entries: List<StoreGuildsSorted.Entry>,
        guildId: Long,
        folderId: Long,
        openFolderIds: Set<Long> = emptySet(),
        excludedGuildIds: Set<Long> = emptySet(),
    ): List<StoreGuildsSorted.Entry> {
        validate(entries, excludedGuildIds)
        if (folderId in openFolderIds) return entries
        val (withoutGuild, guild) = removeGuild(entries, guildId)
        if (guild == null) return entries
        var found = false
        val result = withoutGuild.map { entry ->
            if (entry is StoreGuildsSorted.Entry.Folder && entry.id == folderId) {
                found = true
                entry.copy(guilds = entry.guilds + guild)
            } else entry
        }
        return if (found) result.validated(excludedGuildIds) else entries
    }

    fun removeGuildFromFolder(
        entries: List<StoreGuildsSorted.Entry>,
        guildId: Long,
        excludedGuildIds: Set<Long> = emptySet(),
    ): List<StoreGuildsSorted.Entry> {
        validate(entries, excludedGuildIds)
        val result = ArrayList<StoreGuildsSorted.Entry>(entries.size + 1)
        var removed = false
        entries.forEach { entry ->
            if (!removed && entry is StoreGuildsSorted.Entry.Folder) {
                val guild = entry.guilds.firstOrNull { it.id == guildId }
                if (guild != null) {
                    removed = true
                    val remaining = entry.guilds.filterNot { it.id == guildId }
                    if (remaining.isNotEmpty()) result += entry.copy(guilds = remaining)
                    result += StoreGuildsSorted.Entry.SingletonGuild(guild)
                    return@forEach
                }
            }
            result += entry
        }
        return if (removed) result.validated(excludedGuildIds) else entries
    }

    private fun move(
        entries: List<StoreGuildsSorted.Entry>,
        source: EntryKey,
        target: EntryKey,
        after: Boolean,
        excludedGuildIds: Set<Long>,
    ): List<StoreGuildsSorted.Entry> {
        validate(entries, excludedGuildIds)
        if (source == target) return entries
        if (source is EntryKey.Folder) {
            val moving = entries.folder(source.id) ?: return entries
            if (target is EntryKey.Guild && entries.singleton(target.id) == null) return entries
            val remaining = entries.filterNot { it === moving }
            val targetIndex = remaining.topLevelIndex(target)
            if (targetIndex < 0) return entries
            return remaining.toMutableList().apply { add(targetIndex + if (after) 1 else 0, moving) }.validated(excludedGuildIds)
        }

        source as EntryKey.Guild
        val (remaining, guild) = removeGuild(entries, source.id)
        if (guild == null) return entries
        val result = remaining.toMutableList()
        when (target) {
            is EntryKey.Folder -> {
                val index = result.indexOfFirst { it is StoreGuildsSorted.Entry.Folder && it.id == target.id }
                if (index < 0) return entries
                result.add(index + if (after) 1 else 0, StoreGuildsSorted.Entry.SingletonGuild(guild))
            }
            is EntryKey.Guild -> {
                val singletonIndex = result.indexOfFirst { it is StoreGuildsSorted.Entry.SingletonGuild && it.guild.id == target.id }
                if (singletonIndex >= 0) {
                    result.add(singletonIndex + if (after) 1 else 0, StoreGuildsSorted.Entry.SingletonGuild(guild))
                } else {
                    val folderIndex = result.indexOfFirst { it is StoreGuildsSorted.Entry.Folder && it.guilds.any { guild -> guild.id == target.id } }
                    if (folderIndex < 0) return entries
                    val folder = result[folderIndex] as StoreGuildsSorted.Entry.Folder
                    val targetIndex = folder.guilds.indexOfFirst { it.id == target.id }
                    result[folderIndex] = folder.copy(
                        guilds = folder.guilds.toMutableList().apply { add(targetIndex + if (after) 1 else 0, guild) },
                    )
                }
            }
        }
        return result.validated(excludedGuildIds)
    }

    private fun removeGuild(
        entries: List<StoreGuildsSorted.Entry>,
        guildId: Long,
    ): Pair<List<StoreGuildsSorted.Entry>, Guild?> {
        var removed: Guild? = null
        val result = entries.mapNotNull { entry ->
            when {
                removed != null -> entry
                entry is StoreGuildsSorted.Entry.SingletonGuild && entry.guild.id == guildId -> {
                    removed = entry.guild
                    null
                }
                entry is StoreGuildsSorted.Entry.Folder && entry.guilds.any { it.id == guildId } -> {
                    removed = entry.guilds.first { it.id == guildId }
                    entry.copy(guilds = entry.guilds.filterNot { it.id == guildId }).takeIf { it.guilds.isNotEmpty() }
                }
                else -> entry
            }
        }
        return result to removed
    }

    private fun validate(entries: List<StoreGuildsSorted.Entry>, excludedGuildIds: Set<Long>) {
        val guildIds = entries.flatMap { entry ->
            when (entry) {
                is StoreGuildsSorted.Entry.Folder -> entry.guilds.map(Guild::id)
                is StoreGuildsSorted.Entry.SingletonGuild -> listOf(entry.guild.id)
            }
        }
        require(guildIds.size == guildIds.toSet().size) { "Guild entries contain duplicate guild IDs" }
        require(guildIds.none(excludedGuildIds::contains)) { "Lurking or pending guilds cannot be reordered" }
        val folderIds = entries.filterIsInstance<StoreGuildsSorted.Entry.Folder>().map(StoreGuildsSorted.Entry.Folder::id)
        require(folderIds.size == folderIds.toSet().size) { "Guild entries contain duplicate folder IDs" }
    }

    private fun List<StoreGuildsSorted.Entry>.validated(excludedGuildIds: Set<Long>) = apply { validate(this, excludedGuildIds) }
    private fun List<StoreGuildsSorted.Entry>.folder(id: Long) = filterIsInstance<StoreGuildsSorted.Entry.Folder>().firstOrNull { it.id == id }
    private fun List<StoreGuildsSorted.Entry>.singleton(id: Long) = filterIsInstance<StoreGuildsSorted.Entry.SingletonGuild>().firstOrNull { it.guild.id == id }
    private fun List<StoreGuildsSorted.Entry>.topLevelIndex(key: EntryKey) = indexOfFirst { entry ->
        when (key) {
            is EntryKey.Folder -> entry is StoreGuildsSorted.Entry.Folder && entry.id == key.id
            is EntryKey.Guild -> entry is StoreGuildsSorted.Entry.SingletonGuild && entry.guild.id == key.id
        }
    }
}
