package com.discord.widgets.hubs

import com.discord.api.channel.isDirectory
import com.discord.api.permission.Permission
import com.discord.app.AppLog
import com.discord.app.AppViewModel
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuildSelected
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * Splits the servers the user administers into "can be added to this hub" and "already added".
 *
 * Scoped to the activity so the list survives the push to [WidgetHubDescription] and back.
 *
 * The store projection was reconstructed from the DEX (`javap -c` of the reference's
 * `observeStores` lambda) because both decompilers fail on this class; the notable details it settles
 * are that eligibility is [Permission.ADMINISTRATOR] — not `MANAGE_GUILD` — and that the directory
 * channel is taken from the current selection only when that selection really is a directory channel,
 * otherwise from the hub guild's first directory channel.
 */
class WidgetHubAddServerViewModel(
    storeObservable: Observable<HubAddServerState> = observeStores(),
    private val directoriesStore: HubDirectoriesStore = HubStores.directories,
) : AppViewModel<HubAddServerState>(HubAddServerState()) {

    init {
        storeObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetHubAddServerViewModel::class.java) { handleStoreUpdate(it) }
    }

    /**
     * Everything except the selected tab, which is user state the store knows nothing about.
     */
    private fun handleStoreUpdate(state: HubAddServerState) {
        updateViewState(
            requireViewState().copy(
                hubName = state.hubName,
                directoryChannelId = state.directoryChannelId,
                selectableGuilds = state.selectableGuilds,
                addedGuilds = state.addedGuilds,
                addedDirectories = state.addedDirectories,
            ),
        )
    }

    fun getChannelId(): Long? = viewState?.directoryChannelId

    fun getHubName(): String = viewState?.hubName.orEmpty()

    fun removeGuild(guildId: Long, directoryChannelId: Long) {
        directoriesStore.removeServerFromDirectory(directoryChannelId, guildId)
    }

    fun setIndex(index: Int) {
        updateViewState(requireViewState().copy(selectedIndex = index))
    }

    companion object {
        /** Tab index of the "choose a server" segment. */
        const val CHOOSE_GUILD: Int = 0

        /** Tab index of the "already added" segment. */
        const val GUILDS_ADDED: Int = 1

        fun observeStores(
            observationDeck: ObservationDeck = ObservationDeckProvider.get(),
            guildsStore: StoreGuilds = StoreStream.getGuilds(),
            guildsSelected: StoreGuildSelected = StoreStream.getGuildSelected(),
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
            channelsStore: StoreChannels = StoreStream.getChannels(),
            channelsSelectedStore: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            directoriesStore: HubDirectoriesStore = HubStores.directories,
        ): Observable<HubAddServerState> = observationDeck.connectRx(
            guildsStore,
            guildsSelected,
            permissionsStore,
            channelsStore,
            channelsSelectedStore,
            directoriesStore,
        ).map {
            buildState(
                guildsStore,
                guildsSelected,
                permissionsStore,
                channelsStore,
                channelsSelectedStore,
                directoriesStore,
            )
        }

        private fun buildState(
            guildsStore: StoreGuilds,
            guildsSelected: StoreGuildSelected,
            permissionsStore: StorePermissions,
            channelsStore: StoreChannels,
            channelsSelectedStore: StoreChannelsSelected,
            directoriesStore: HubDirectoriesStore,
        ): HubAddServerState {
            val allGuilds = guildsStore.getGuilds().values
            val selectedGuildId = guildsSelected.getSelectedGuildId()
            val hubGuild = allGuilds.firstOrNull { it.id == selectedGuildId }
            if (hubGuild == null) {
                AppLog.e("Missing guild for guild id: $selectedGuildId")
            }

            val guildPermissions = permissionsStore.getGuildPermissions()
            val directoryChannel = channelsSelectedStore.getSelectedChannel()?.takeIf { it.isDirectory() }
                ?: channelsStore.getChannelsForGuild(hubGuild?.id ?: -1L)
                    .values
                    .firstOrNull { it.isDirectory() }
            if (directoryChannel == null) {
                AppLog.e("Guild has no directory channels for id: ${hubGuild?.id}")
            }

            val entries = directoryChannel
                ?.let { directoriesStore.getDirectoriesForChannel(it.id)?.invoke() }
                .orEmpty()
            val listedGuildIds = entries.map { it.guild.id }.toSet()

            val (selectableGuilds, addedGuilds) = allGuilds
                .filter { PermissionUtils.can(Permission.ADMINISTRATOR, guildPermissions[it.id]) }
                .partition { it.id !in listedGuildIds }

            val addedGuildIds = addedGuilds.map { it.id }
            return HubAddServerState(
                hubName = hubGuild?.name.orEmpty(),
                directoryChannelId = directoryChannel?.id,
                selectableGuilds = selectableGuilds,
                addedGuilds = addedGuilds,
                addedDirectories = entries.filter { it.guild.id in addedGuildIds },
            )
        }
    }
}
