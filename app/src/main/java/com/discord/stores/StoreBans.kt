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
 * 1. **[observeBans] fires the REST fetch itself**, on every call, rather than exposing a separate
 *    `fetchBans`. The ban screen is the only consumer and it subscribes once, so the reference
 *    never bothered to separate the two.
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
    private var bannedUsersSnapshot: Map<Long, Map<Long, ModelBan>> = emptyMap()

    /** `null` until the guild's ban list has been fetched at least once. */
    fun getBans(guildId: Long): Map<Long, ModelBan>? = bannedUsersSnapshot[guildId]

    /**
     * Emits the guild's bans, starting the fetch that populates them.
     *
     * `null` while the first response is outstanding, which is how the screen tells "loading" from
     * "no bans".
     */
    fun observeBans(guildId: Long): Observable<Map<Long, ModelBan>?> {
        fetchBans(guildId)
        return observationDeck.connectRx(this) { getBans(guildId) }
    }

    private fun fetchBans(guildId: Long) {
        val api = restApi() ?: return
        api.getBans(guildId)
            .restSubscribeOn(false)
            .appSubscribe(StoreBans::class.java) { bans ->
                dispatcher.schedule { handleBansLoaded(guildId, bans) }
            }
    }

    @StoreThread
    internal fun handleBansLoaded(guildId: Long, bans: List<ModelBan>) {
        val guildBans = bannedUsers.getOrPut(guildId, ::HashMap)
        bans.forEach { ban -> guildBans[ban.user.id] = ban }
        markChanged()
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
        if (bannedUsers.remove(guildId) != null) markChanged()
    }

    @StoreThread
    internal fun handleConnectionOpen() {
        bannedUsers.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        bannedUsersSnapshot = bannedUsers.mapValues { (_, bans) -> HashMap(bans) }
    }
}
