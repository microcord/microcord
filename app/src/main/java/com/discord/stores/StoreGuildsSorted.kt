package com.discord.stores

import com.discord.models.domain.ModelGuildFolder
import com.discord.models.guild.Guild
import com.discord.stores.updates.ObservationDeck
import rx.Observable

class StoreGuildsSorted(
    private val observationDeck: ObservationDeck,
    private val dispatcher: Dispatcher,
) : StoreV2() {
    sealed class Entry {
        abstract fun asModelGuildFolder(): ModelGuildFolder

        data class Folder(
            val id: Long,
            val guilds: List<Guild>,
            val color: Int?,
            val name: String?,
        ) : Entry() {
            override fun asModelGuildFolder() = ModelGuildFolder(id, guilds.map(Guild::id), color, name)
        }

        data class SingletonGuild(val guild: Guild) : Entry() {
            override fun asModelGuildFolder() = ModelGuildFolder(null, listOf(guild.id))
        }
    }

    private val positions = ArrayList<ModelGuildFolder>()
    private val entries = ArrayList<Entry>()
    private var entriesSnapshot: List<Entry> = emptyList()
    private var guilds: Map<Long, Guild> = emptyMap()
    private var unavailableGuildIds: Set<Long> = emptySet()
    private var lurkingGuildIds: Set<Long> = emptySet()
    private var mutedGuildIds: Set<Long> = emptySet()
    private var guildsJoinedAt: Map<Long, Long> = emptyMap()

    fun getEntries(): List<Entry> = entriesSnapshot

    fun getOrderedGuilds(): LinkedHashMap<Long, Guild> = LinkedHashMap<Long, Guild>().apply {
        entriesSnapshot.forEach { entry ->
            when (entry) {
                is Entry.Folder -> entry.guilds.forEach { put(it.id, it) }
                is Entry.SingletonGuild -> put(entry.guild.id, entry.guild)
            }
        }
    }

    fun observeEntries(): Observable<List<Entry>> =
        observationDeck.connectRx(this, generator = ::getEntries).distinctUntilChanged()

    fun observeOrderedGuilds(): Observable<LinkedHashMap<Long, Guild>> =
        observationDeck.connectRx(this, generator = ::getOrderedGuilds).distinctUntilChanged()

    fun setPositions(guildPositions: List<Entry>) = dispatcher.schedule { handlePositions(guildPositions) }

    @StoreThread
    internal fun handlePositions(guildPositions: List<Entry>) {
        positions.clear()
        positions.addAll(guildPositions.map(Entry::asModelGuildFolder))
        validateAndRebuild()
    }

    fun update(
        guilds: Map<Long, Guild>,
        unavailableGuildIds: Set<Long>,
        lurkingGuildIds: Set<Long>,
        userPositions: List<ModelGuildFolder>,
        mutedGuildIds: Set<Long>,
        guildsJoinedAt: Map<Long, Long>,
    ) {
        this.guilds = guilds
        this.unavailableGuildIds = unavailableGuildIds
        this.lurkingGuildIds = lurkingGuildIds
        this.mutedGuildIds = mutedGuildIds
        this.guildsJoinedAt = guildsJoinedAt
        positions.clear()
        positions.addAll(if (userPositions.isEmpty()) fallbackPositions() else userPositions)
        validateAndRebuild()
    }

    override fun snapshotData() {
        entriesSnapshot = entries.toList()
    }

    private fun fallbackPositions(): List<ModelGuildFolder> = guilds.keys.sortedWith(
        compareBy<Long> { it in mutedGuildIds }
            .thenByDescending { guildsJoinedAt[it] ?: 0L }
            .thenBy { guilds.getValue(it).name }
            .thenBy { it },
    ).map { ModelGuildFolder(null, listOf(it)) }

    private fun validateAndRebuild() {
        val positionedIds = positions.flatMapTo(HashSet(), ModelGuildFolder::guildIds)
        guilds.keys.filter { it !in positionedIds && it !in lurkingGuildIds }
            .sorted().asReversed().forEach { positions.add(0, ModelGuildFolder(null, listOf(it))) }

        val seenGuildIds = HashSet<Long>()
        positions.indices.forEach { index ->
            val position = positions[index]
            positions[index] = position.copy(
                guildIds = position.guildIds.filter {
                    it in guilds && it !in unavailableGuildIds && it !in lurkingGuildIds && seenGuildIds.add(it)
                },
            )
        }
        lurkingGuildIds.filter(guilds::containsKey).sorted().asReversed().forEach {
            positions.add(0, ModelGuildFolder(null, listOf(it)))
        }
        positions.removeAll { it.guildIds.isEmpty() }

        entries.clear()
        positions.forEach { position ->
            if (position.id != null) {
                entries += Entry.Folder(
                    position.id,
                    position.guildIds.map(guilds::getValue),
                    position.color,
                    position.name,
                )
            } else {
                entries += Entry.SingletonGuild(guilds.getValue(position.guildIds.first()))
            }
        }
        markChanged()
    }
}
