package com.discord.widgets.directories

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.channel.isDirectory
import com.discord.api.channel.isHQDirectoryChannel
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.hubs.DirectoryEntryCategory
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuildSelected
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreReadStates
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.Failure
import com.discord.stores.utilities.Loading
import com.discord.stores.utilities.RestCallState
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.contextPropertiesOf
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * State for the whole directory channel surface.
 *
 * One instance is shared by [WidgetDirectoryChannel] and every [WidgetDirectoryCategory] page it
 * hosts (they resolve it against the activity), so the tab strip, the tab counts and each page all
 * read the same snapshot and cannot disagree.
 *
 * It also owns the two side effects the surface has: fetching the channel's entries and counts when
 * a directory channel becomes selected, and marking a directory channel read when the user leaves
 * it.
 */
class WidgetDirectoriesViewModel(
    private val directoriesStore: DirectoriesStore = DirectoryStores.directories,
    channelsSelectedStore: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    channelsStore: StoreChannels = StoreStream.getChannels(),
    readStatesStore: StoreReadStates = StoreStream.getReadStates(),
    storeObservable: Observable<ViewState> = observeStores(),
) : AppViewModel<WidgetDirectoriesViewModel.ViewState>() {

    /**
     * @param directories the channel's entries. [Default] means "not asked yet", which is what the
     *   surface shows a spinner for; [Failure] is what it shows the retry state for.
     * @param tabs category key → number of entries in it, driving the counts in the tab strip.
     */
    data class ViewState(
        val guild: Guild? = null,
        val channel: Channel? = null,
        val joinedGuildIds: Set<Long> = emptySet(),
        val adminGuildIds: Set<Long> = emptySet(),
        val hasAddGuildPermissions: Boolean = true,
        val directories: RestCallState<List<DirectoryEntryGuild>> = Default,
        val tabs: RestCallState<Map<Int, Int>> = Default,
    ) {
        /** Every entry in the channel, in server order, joined with the viewer's relationship to it. */
        val allDirectoryEntryData: List<DirectoryEntryData> by lazy {
            directories().orEmpty().map { toEntryData(it) }
        }

        /** The same entries, bucketed by [DirectoryEntryGuild.primaryCategoryId]. */
        val directoryEntryData: Map<Int, List<DirectoryEntryData>> by lazy {
            directories().orEmpty().groupBy({ it.primaryCategoryId }, { toEntryData(it) })
        }

        /**
         * The tab strip: always a leading "Home" tab with no count, then one tab per category the
         * server reported a count for.
         */
        val mappedTabs: List<Pair<DirectoryEntryCategory, Int>> by lazy {
            val isHQ = channel?.isHQDirectoryChannel() == true
            listOf(DirectoryEntryCategory.Home to 0) +
                tabs().orEmpty().map { (key, count) ->
                    DirectoryEntryCategory.findByKey(key, isHQ) to count
                }
        }

        /** What the surface should render while it has no entries to show. */
        val loadState: DirectoryLoadState
            get() = when {
                allDirectoryEntryData.isNotEmpty() -> DirectoryLoadState.Content
                directories is Loading || directories is Default -> DirectoryLoadState.Loading
                directories is Failure -> DirectoryLoadState.Error
                else -> DirectoryLoadState.Empty
            }

        private fun toEntryData(entry: DirectoryEntryGuild) = DirectoryEntryData(
            entry = entry,
            hasJoinedGuild = joinedGuildIds.contains(entry.guild.id),
            hasEditPermissions = adminGuildIds.contains(entry.guild.id),
        )
    }

    init {
        // Only a directory channel drives this surface: while the user is looking at anything else
        // the last directory state is held rather than cleared, so returning to the tab does not
        // flash an empty page.
        storeObservable
            .computationLatest()
            .filter { it.channel?.isDirectory() == true }
            .ui(this)
            .appSubscribe(WidgetDirectoriesViewModel::class.java) { updateViewState(it) }

        channelsSelectedStore.observeSelectedChannel()
            .distinctUntilChanged()
            .filterNull()
            .filter { it.isDirectory() }
            .appSubscribe(WidgetDirectoriesViewModel::class.java) { channel -> fetch(channel.id) }

        // Leaving a directory channel marks it read. Directory channels carry no messages, so the
        // ordinary chat read-state path never runs for them.
        channelsSelectedStore.observePreviousId()
            .distinctUntilChanged()
            .filter { channelsStore.findChannelById(it)?.isDirectory() == true }
            .appSubscribe(WidgetDirectoriesViewModel::class.java) { readStatesStore.markAsRead(it) }
    }

    /** The hub's name, for menu and dialog copy. Empty until the guild resolves. */
    fun getHubName(): String = viewState?.guild?.name.orEmpty()

    /** Re-runs the entry and count fetches for the selected directory channel. */
    fun retry() {
        viewState?.channel?.let { fetch(it.id) }
    }

    private fun fetch(channelId: Long) {
        directoriesStore.fetchDirectoriesForChannel(channelId)
        directoriesStore.fetchEntryCountsForChannel(channelId)
    }

    /**
     * Joins [guildId] on the viewer's behalf, attributing the join to the directory it was listed in
     * so the hub's analytics and the server's member source are correct.
     */
    fun joinGuild(context: Context, guildId: Long, directoryChannelId: Long) {
        RestAPI.apiOrNull
            ?.joinGuild(
                guildId = guildId,
                isLurker = false,
                sessionId = null,
                directoryChannelId = directoryChannelId,
                body = RestAPIParams.InviteCode(),
                context = contextPropertiesOf("source" to DIRECTORY_JOIN_GUILD_SOURCE),
            )
            ?.restSubscribeOn()
            ?.ui(this)
            ?.appSubscribe(WidgetDirectoriesViewModel::class.java, context = context) { }
    }

    /** Removes [guildId] from the directory in [directoryChannelId]. */
    fun removeGuild(guildId: Long, directoryChannelId: Long) {
        directoriesStore.removeServerFromDirectory(directoryChannelId, guildId)
    }

    companion object {

        @JvmStatic
        fun observeStores(
            observationDeck: ObservationDeck = ObservationDeckProvider.get(),
            guildsStore: StoreGuilds = StoreStream.getGuilds(),
            guildSelectedStore: StoreGuildSelected = StoreStream.getGuildSelected(),
            channelsSelectedStore: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            directoriesStore: DirectoriesStore = DirectoryStores.directories,
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
        ): Observable<ViewState> = observationDeck.connectRx(
            guildsStore,
            guildSelectedStore,
            directoriesStore,
            channelsSelectedStore,
            permissionsStore,
        ) {
            buildViewState(
                guildsStore,
                guildSelectedStore,
                channelsSelectedStore,
                directoriesStore,
                permissionsStore,
            )
        }

        internal fun buildViewState(
            guildsStore: StoreGuilds,
            guildSelectedStore: StoreGuildSelected,
            channelsSelectedStore: StoreChannelsSelected,
            directoriesStore: DirectoriesStore,
            permissionsStore: StorePermissions,
        ): ViewState {
            val channel = channelsSelectedStore.getSelectedChannel()
            val guild = guildsStore.getGuild(guildSelectedStore.getSelectedGuildId())
            val guildPermissions = permissionsStore.getGuildPermissions()
            return ViewState(
                guild = guild,
                channel = channel,
                // Every guild the viewer belongs to; the row uses it to pick "Join" vs "Joined".
                joinedGuildIds = guildsStore.getGuilds().keys,
                // Guilds the viewer administers, which are the listings they may edit or remove.
                adminGuildIds = guildsStore.getGuilds().values
                    .filter { PermissionUtils.can(Permission.ADMINISTRATOR, guildPermissions[it.id]) }
                    .map { it.id }
                    .toSet(),
                // Adding a server to the hub posts into the directory channel.
                hasAddGuildPermissions = PermissionUtils.can(
                    Permission.SEND_MESSAGES,
                    channel?.let { permissionsStore.getPermissionsByChannel()[it.id] },
                ),
                directories = channel?.let { directoriesStore.getDirectoriesForChannel(it.id) } ?: Default,
                tabs = channel?.let { directoriesStore.getEntryCountsForChannel(it.id) } ?: Default,
            )
        }
    }
}

/** What a directory surface renders when it has no entries to show. */
enum class DirectoryLoadState { Loading, Content, Empty, Error }
