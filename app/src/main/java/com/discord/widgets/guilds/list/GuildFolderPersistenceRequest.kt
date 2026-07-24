package com.discord.widgets.guilds.list

import com.discord.models.domain.ModelGuildFolder
import com.discord.stores.StoreGuildsSorted

class GuildFolderPersistenceRequest(
    private val persist: (List<ModelGuildFolder>) -> Unit,
) {
    fun submit(entries: List<StoreGuildsSorted.Entry>) = persist(entries.map(StoreGuildsSorted.Entry::asModelGuildFolder))
}
