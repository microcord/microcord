package com.discord.stores

import android.content.Context
import com.discord.api.directory.DirectoryEntryEvent
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.api.directory.DirectoryEntryType
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.utilities.RestCallState
import com.discord.stores.utilities.Success
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.widgets.directories.DirectoriesStore
import com.discord.widgets.hubs.HubDirectoriesStore
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers

/** Directory listings, category counts, and Hub scheduled-event listings. */
class StoreDirectories(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val guildStore: StoreGuilds,
    private val guildScheduledEventsStore: StoreGuildScheduledEvents,
    private val restApi: RestAPIInterface,
    private val hubEventsEnabled: (Long) -> Boolean,
    private val requestScheduler: Scheduler = Schedulers.io(),
) : StoreV2(), DirectoriesStore, HubDirectoriesStore {

    private val directoriesMap = LinkedHashMap<Long, RestCallState<List<DirectoryEntryGuild>>>()
    private var directoriesMapSnapshot: Map<Long, RestCallState<List<DirectoryEntryGuild>>> = emptyMap()

    private val entryCountMap = LinkedHashMap<Long, RestCallState<Map<Int, Int>>>()
    private var entryCountMapSnapshot: Map<Long, RestCallState<Map<Int, Int>>> = emptyMap()

    private val directoryGuildScheduledEventsMap =
        LinkedHashMap<Long, RestCallState<List<DirectoryEntryEvent>>>()
    private var directoryGuildScheduledEventsMapSnapshot:
        Map<Long, RestCallState<List<DirectoryEntryEvent>>> = emptyMap()

    private val discordHubClickedPersister = Persister(DISCORD_HUB_VERIFICATION_CLICKED_KEY, false)
    private val guildScheduledEventsHeaderDismissed =
        Persister(GUILD_SCHEDULED_EVENTS_HEADER_DISMISSED, false)
    private val hubNamePromptPersister = Persister(HUB_NAME_PROMPT, emptySet<Long>())

    override fun init(context: Context) {
        super.init(context)
        guildStore.observeGuilds()
            .leadingEdgeThrottle(1L, TimeUnit.SECONDS)
            .computationLatest()
            .map { guilds -> guilds.values.filter { it.isHub() }.mapTo(HashSet()) { it.id } }
            .distinctUntilChanged()
            .appSubscribe(StoreDirectories::class.java) { hubGuildIds ->
                hubNamePromptPersister.set(hubNamePromptPersister.get().intersect(hubGuildIds))
            }
    }

    override fun getDirectoriesForChannel(channelId: Long): RestCallState<List<DirectoryEntryGuild>>? =
        directoriesMapSnapshot[channelId]

    override fun fetchDirectoriesForChannel(channelId: Long) {
        restApi.getDirectoryEntries(channelId)
            .map { entries -> entries.sortedByDescending { it.guild.approximateMemberCount ?: 0 } }
            .executeRequest(requestScheduler) { response ->
                dispatcher.schedule {
                    directoriesMap[channelId] = response
                    markChanged()
                }
            }
    }

    override fun getEntryCountsForChannel(channelId: Long): RestCallState<Map<Int, Int>>? =
        entryCountMapSnapshot[channelId]

    override fun fetchEntryCountsForChannel(channelId: Long) {
        restApi.getEntryCounts(channelId).executeRequest(requestScheduler) { response ->
            dispatcher.schedule {
                entryCountMap[channelId] = response
                markChanged()
            }
        }
    }

    override fun addServerToDirectory(channelId: Long, directoryEntry: DirectoryEntryGuild) {
        dispatcher.schedule {
            var replaced = false
            val entries = directoriesMap[channelId]?.invoke().orEmpty().map { existing ->
                if (existing.guild.id == directoryEntry.guild.id) {
                    replaced = true
                    directoryEntry
                } else {
                    existing
                }
            }
            directoriesMap[channelId] = Success(if (replaced) entries else entries + directoryEntry)
            markChanged()
        }
    }

    override fun removeServerFromDirectory(channelId: Long, guildId: Long) {
        restApi.removeServerFromHub(channelId, guildId).executeRequest(requestScheduler) { response ->
            if (response !is Success) return@executeRequest
            dispatcher.schedule {
                val entries = directoriesMap[channelId]?.invoke().orEmpty()
                    .filterNot { it.guild.id == guildId }
                directoriesMap[channelId] = Success(entries)
                markChanged()
            }
        }
    }

    override fun getGuildScheduledEventsForChannel(
        directoryChannelId: Long,
    ): RestCallState<List<DirectoryEntryEvent>>? =
        directoryGuildScheduledEventsMapSnapshot[directoryChannelId]

    override fun fetchGuildScheduledEventsForChannel(guildId: Long, directoryChannelId: Long) {
        if (!hubEventsEnabled(guildId)) return
        restApi.getDirectoryGuildScheduledEvents(
            directoryChannelId,
            DirectoryEntryType.GuildScheduledEvent.key,
        ).executeRequest(requestScheduler) { response ->
            dispatcher.schedule {
                directoryGuildScheduledEventsMap[directoryChannelId] = response
                if (response is Success) {
                    guildScheduledEventsStore.addMeRsvpsForEvent(
                        response.response.mapNotNull { entry ->
                            entry.guildScheduledEvent.takeIf { it.userRsvp != null }
                                ?.let { event -> event.guildId to event.id }
                        },
                    )
                }
                markChanged()
            }
        }
    }

    fun getAndSetSeenNamePrompt(guildId: Long): Boolean {
        val wasSeen = guildId in hubNamePromptPersister.get()
        hubNamePromptPersister.set(hubNamePromptPersister.get() + guildId, true)
        return wasSeen
    }

    fun markDiscordHubClicked() {
        discordHubClickedPersister.set(true, true)
    }

    fun observeDiscordHubClicked(): Observable<Boolean> =
        discordHubClickedPersister.getObservable().distinctUntilChanged()

    override fun getGuildScheduledEventsHeaderDismissed(): Boolean =
        guildScheduledEventsHeaderDismissed.get()

    override fun markGuildScheduledEventsHeaderDismissed() {
        guildScheduledEventsHeaderDismissed.set(true, true)
    }

    fun observeDirectories(): Observable<Map<Long, RestCallState<List<DirectoryEntryGuild>>>> =
        observationDeck.connectRx(this) { directoriesMapSnapshot }

    fun observeDirectoriesForChannel(
        channelId: Long,
    ): Observable<RestCallState<List<DirectoryEntryGuild>>?> =
        observationDeck.connectRx(this) { directoriesMapSnapshot[channelId] }

    fun observeDirectoryGuildScheduledEvents():
        Observable<Map<Long, RestCallState<List<DirectoryEntryEvent>>>> =
        observationDeck.connectRx(this) { directoryGuildScheduledEventsMapSnapshot }

    override fun snapshotData() {
        directoriesMapSnapshot = HashMap(directoriesMap)
        entryCountMapSnapshot = HashMap(entryCountMap)
        directoryGuildScheduledEventsMapSnapshot = HashMap(directoryGuildScheduledEventsMap)
    }

    private companion object {
        const val DISCORD_HUB_VERIFICATION_CLICKED_KEY = "hub_verification_clicked_key"
        const val GUILD_SCHEDULED_EVENTS_HEADER_DISMISSED = "guild_scheduled_events_header_dismissed"
        const val HUB_NAME_PROMPT = "hub_name_prompt"
    }
}
