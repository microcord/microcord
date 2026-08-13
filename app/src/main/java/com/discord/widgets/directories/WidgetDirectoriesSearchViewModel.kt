package com.discord.widgets.directories

import android.content.Context
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.api.guild.GuildFeature
import com.discord.stores.JoinGuildSources
import com.discord.stores.StoreGuildSelected
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.Failure
import com.discord.stores.utilities.Loading
import com.discord.stores.utilities.RestCallState
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.contextPropertiesOf
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * State for the directory search screen.
 *
 * Unlike [WidgetDirectoriesViewModel] the results here are not store-backed — a search is a one-shot
 * REST call whose whole lifecycle the screen owns — so [directoriesState] is the state machine the
 * screen renders (`Default` before the first search, `Loading`, `Success`, `Failure`) and
 * [directories] is the last successful result, kept across a failed re-search so the list does not
 * blank out.
 *
 * The store-backed half (which guilds the viewer is in, which they administer, whether they may add
 * a server) still comes from the stores and is merged in by [handleNewState].
 */
class WidgetDirectoriesSearchViewModel(
    val channelId: Long,
    private val directoriesStore: DirectoriesStore = DirectoryStores.directories,
    storeObservable: Observable<ViewState> = observeStores(channelId),
) : AppViewModel<WidgetDirectoriesSearchViewModel.ViewState>(ViewState()) {

    data class ViewState(
        val guild: Guild? = null,
        val currentSearchTerm: String = "",
        val directories: List<DirectoryEntryGuild> = emptyList(),
        val joinedGuildIds: Set<Long> = emptySet(),
        val adminGuildIds: Set<Long> = emptySet(),
        val hasAddGuildPermissions: Boolean = true,
        val directoriesState: RestCallState<List<DirectoryEntryGuild>> = Default,
    ) {
        val directoryEntryData: List<DirectoryEntryData> by lazy {
            directories.map { entry ->
                DirectoryEntryData(
                    entry = entry,
                    hasJoinedGuild = joinedGuildIds.contains(entry.guild.id),
                    hasEditPermissions = adminGuildIds.contains(entry.guild.id),
                )
            }
        }

        /** What the screen should render while it has no results to show. */
        val loadState: DirectoryLoadState
            get() = when {
                directories.isNotEmpty() -> DirectoryLoadState.Content
                directoriesState is Loading -> DirectoryLoadState.Loading
                directoriesState is Failure -> DirectoryLoadState.Error
                else -> DirectoryLoadState.Empty
            }
    }

    init {
        storeObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetDirectoriesSearchViewModel::class.java) { handleNewState(it) }
    }

    /**
     * Folds a store snapshot into the current state, leaving the search term, the results and the
     * request state — none of which the stores know about — untouched.
     */
    private fun handleNewState(storeState: ViewState) {
        val current = viewState ?: return
        updateViewState(
            current.copy(
                guild = storeState.guild,
                joinedGuildIds = storeState.joinedGuildIds,
                adminGuildIds = storeState.adminGuildIds,
                hasAddGuildPermissions = storeState.hasAddGuildPermissions,
            ),
        )
    }

    fun getHubName(): String = viewState?.guild?.name.orEmpty()

    /**
     * `true` when [searchTerm] should not be sent: it is blank, or it is the search already showing.
     * Drives whether the search button is offered at all.
     */
    fun shouldNotSearch(searchTerm: CharSequence): Boolean =
        searchTerm.isBlank() || viewState?.currentSearchTerm == searchTerm.toString()

    fun searchForDirectories(channelId: Long, query: CharSequence) {
        val current = viewState ?: return
        if (shouldNotSearch(query)) return
        val api = RestAPI.apiOrNull ?: return
        api.searchServers(channelId, query.toString()).executeRequest { state ->
            updateViewState(
                current.copy(
                    currentSearchTerm = query.toString(),
                    // A failed re-search keeps the previous results rather than emptying the list.
                    directories = state() ?: current.directories,
                    directoriesState = state,
                ),
            )
        }
    }

    /** Retries the search currently shown. */
    fun retry() {
        val term = viewState?.currentSearchTerm ?: return
        if (term.isBlank()) return
        // The guard in searchForDirectories exists to stop duplicate searches; a retry is deliberate.
        updateViewState(requireViewState().copy(currentSearchTerm = ""))
        searchForDirectories(channelId, term)
    }

    /** Tapping the row previews rather than joins; only previewable servers can be lurked. */
    fun previewGuild(guildId: Long) {
        val previewable = viewState?.directories
            ?.firstOrNull { it.entityId == guildId }
            ?.guild?.features
            ?.contains(GuildFeature.PREVIEW_ENABLED) == true
        if (!previewable) return
        StoreStream.getLurking().startLurkingAndNavigate(guildId, null, JoinGuildSources.DIRECTORY_ENTRY)
    }

    fun joinGuild(context: Context, guildId: Long, directoryChannelId: Long) {
        RestAPI.apiOrNull
            ?.joinGuild(
                guildId = guildId,
                isLurker = false,
                sessionId = null,
                directoryChannelId = directoryChannelId,
                body = RestAPIParams.InviteCode(),
                context = contextPropertiesOf("source" to JoinGuildSources.DIRECTORY_ENTRY),
            )
            ?.restSubscribeOn()
            ?.ui(this)
            ?.appSubscribe(WidgetDirectoriesSearchViewModel::class.java, context = context) { }
    }

    fun removeGuild(guildId: Long, directoryChannelId: Long) {
        directoriesStore.removeServerFromDirectory(directoryChannelId, guildId)
    }

    companion object {

        @JvmStatic
        fun observeStores(
            channelId: Long,
            observationDeck: ObservationDeck = ObservationDeckProvider.get(),
            guildsStore: StoreGuilds = StoreStream.getGuilds(),
            guildSelectedStore: StoreGuildSelected = StoreStream.getGuildSelected(),
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
        ): Observable<ViewState> = observationDeck.connectRx(
            guildsStore,
            guildSelectedStore,
            permissionsStore,
        ) {
            val guildPermissions = permissionsStore.getGuildPermissions()
            ViewState(
                guild = guildsStore.getGuild(guildSelectedStore.getSelectedGuildId()),
                joinedGuildIds = guildsStore.getGuilds().keys,
                adminGuildIds = guildsStore.getGuilds().values
                    .filter { PermissionUtils.can(Permission.ADMINISTRATOR, guildPermissions[it.id]) }
                    .map { it.id }
                    .toSet(),
                hasAddGuildPermissions = PermissionUtils.can(
                    Permission.SEND_MESSAGES,
                    permissionsStore.getPermissionsByChannel()[channelId],
                ),
            )
        }
    }
}
