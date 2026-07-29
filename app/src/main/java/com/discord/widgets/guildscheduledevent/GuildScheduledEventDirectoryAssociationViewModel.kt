package com.discord.widgets.guildscheduledevent

import com.discord.api.directory.DirectoryEntryType
import com.discord.api.guild.GuildFeature
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.rest.RestAPI

class GuildScheduledEventDirectoryAssociationViewModel(
    guildId: Long,
    existingGuildScheduledEventId: Long?,
    guildsStore: StoreGuilds = StoreStream.getGuilds(),
    restApi: RestAPIInterface = RestAPI.api,
) : AppViewModel<GuildScheduledEventDirectoryAssociationState>(
    GuildScheduledEventDirectoryAssociationState(),
) {
    init {
        val guild = guildsStore.getGuild(guildId)
        if (guild == null || guild.hasFeature(GuildFeature.HAS_DIRECTORY_ENTRY)) {
            restApi.getDirectoryEntryBroadcastInfo(
                guildId,
                existingGuildScheduledEventId,
                DirectoryEntryType.GuildScheduledEvent.key,
            ).executeRequest { enabledAsync ->
                updateViewState(GuildScheduledEventDirectoryAssociationState(enabledAsync))
            }
        }
    }
}
