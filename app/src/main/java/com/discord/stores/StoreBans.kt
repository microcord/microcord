package com.discord.stores

import com.discord.models.domain.ModelBan
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The ban list of every guild whose ban screen has been opened this session.
 *
 * Two deliberate shapes come straight from the reference and are easy to mistake for bugs:
 *
 * 1. **[observeBans] is a pure read.** It deliberately does not kick off a fetch: the screen
 *    re-subscribes whenever the list changes, so a self-firing observation would request the next
 *    page in response to its own result and walk the entire list. Pages come from [loadMoreBans],
 *    which the screen calls once on open and again as it is scrolled.
 * 2. **[handleBanAdd] / [handleBanRemove] are no-ops for guilds that have never been fetched.**
 *    A gateway ban event for a guild whose list is absent must not create a one-entry list — that
 *    would render as "this server has exactly one ban". Only guilds with a loaded list are updated.
 *
 * `GUILD_BAN_ADD` / `GUILD_BAN_REMOVE` carry `guild_id`; the REST list does not, so REST rows are
 * re-associated by the caller (see [ModelBan.NO_GUILD]).
 */
class StoreBans(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    private val bannedUsers = HashMap<Long, HashMap<Long, ModelBan>>()

    /** Guilds with a page request in flight, so scrolling cannot stack duplicate requests. */
    private val fetching = HashSet<Long>()

    /** Guilds whose last page came back short; there is nothing further to ask for. */
    private val exhausted = HashSet<Long>()
    private var bannedUsersSnapshot: Map<Long, Map<Long, ModelBan>> = emptyMap()

    /** `null` until the guild's ban list has been fetched at least once. */
    fun getBans(guildId: Long): Map<Long, ModelBan>? = bannedUsersSnapshot[guildId]

    /**
     * Emits the guild's bans, starting the fetch that populates them.
     *
     * `null` while the first response is outstanding, which is how the screen tells "loading" from
     * "no bans".
     */
    fun observeBans(guildId: Long): Observable<Map<Long, ModelBan>?> =
        observationDeck.connectRx(this) { getBans(guildId) }

    /**
     * Loads the next page of bans, or the first one if none are loaded yet.
     *
     * Pages are pulled on demand rather than walked to completion: a large guild's ban list is
     * thousands of rows the user has not asked to see. The endpoint returns ascending by user id,
     * so the cursor is simply the highest id held so far, and a short page means there is no more.
     */
    fun loadMoreBans(guildId: Long) {
        if (guildId in fetching || guildId in exhausted) return
        val api = restApi() ?: return
        val after = bannedUsersSnapshot[guildId]?.keys?.maxOrNull()
        fetching.add(guildId)
        api.getBans(guildId, after = after)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreBans::class.java,
                errorHandler = { fetching.remove(guildId) },
            ) { bans ->
                dispatcher.schedule {
                    fetching.remove(guildId)
                    if (bans.size < RestAPIInterface.BANS_PAGE_SIZE) exhausted.add(guildId)
                    handleBansLoaded(guildId, bans)
                }
            }
    }

    /**
     * Server-side search, which is the only way to find someone outside the pages already loaded.
     *
     * The matched bans are returned rather than just their ids: filing them is scheduled on the
     * dispatcher, so a caller that looked them up in the store would be reading a map that does not
     * have them yet. They are still filed, so the unfiltered list keeps them.
     */
    fun searchBans(guildId: Long, query: String, userIds: List<Long>): Observable<List<ModelBan>> {
        val api = restApi() ?: return Observable.just(emptyList())
        return api.searchBans(
            guildId,
            query = query.take(SEARCH_QUERY_MAX_LENGTH).takeIf { it.isNotEmpty() },
            userIds = userIds.take(SEARCH_USER_IDS_MAX).takeIf { it.isNotEmpty() },
        )
            .restSubscribeOn(false)
            .doOnNext { bans -> dispatcher.schedule { handleBansLoaded(guildId, bans) } }
    }

    @StoreThread
    internal fun handleBansLoaded(guildId: Long, bans: List<ModelBan>) {
        val guildBans = bannedUsers.getOrPut(guildId, ::HashMap)
        val before = guildBans.size
        bans.forEach { ban -> guildBans[ban.user.id] = ban }
        // A search re-files rows that are already held; publishing that as a change would wake
        // every observer for nothing.
        if (guildBans.size != before || before == 0) markChanged()
    }

    @StoreThread
    fun handleBanAdd(ban: ModelBan) {
        val guildBans = bannedUsers[ban.guildId] ?: return
        guildBans[ban.user.id] = ban
        markChanged()
    }

    @StoreThread
    fun handleBanRemove(ban: ModelBan) {
        val guildBans = bannedUsers[ban.guildId] ?: return
        guildBans.remove(ban.user.id)
        markChanged()
    }

    /**
     * Addition over the reference, which never drops a guild's cached ban list. Without this a
     * guild the user left keeps its bans until logout — harmless but unbounded.
     */
    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        fetching.remove(guildId)
        exhausted.remove(guildId)
        if (bannedUsers.remove(guildId) != null) markChanged()
    }

    @StoreThread
    internal fun handleConnectionOpen() {
        bannedUsers.clear()
        fetching.clear()
        exhausted.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        bannedUsersSnapshot = bannedUsers.mapValues { (_, bans) -> HashMap(bans) }
    }

    private companion object {
        /** Both limits are the endpoint's, not ours. */
        const val SEARCH_QUERY_MAX_LENGTH = 32
        const val SEARCH_USER_IDS_MAX = 10
    }
}
