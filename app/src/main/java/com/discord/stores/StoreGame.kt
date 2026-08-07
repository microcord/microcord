package com.discord.stores

import com.discord.api.game.Game
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

class StoreGame(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {
    private val games = HashMap<Long, Game>()
    private val loading = HashSet<Long>()
    private var snapshot: Map<Long, Game> = emptyMap()

    fun getGames(): Map<Long, Game> = snapshot

    fun fetchIfMissing(gameIds: Collection<Long>) = dispatcher.schedule {
        val missing = gameIds.distinct().filter { it !in games && loading.add(it) }
        if (missing.isEmpty()) return@schedule
        val api = restApi()
        if (api == null) {
            loading.removeAll(missing.toSet())
            return@schedule
        }
        missing.chunked(MAX_GAMES_PER_REQUEST).forEach { requested ->
            api.getGames(requested)
                .restSubscribeOn(false)
                .appSubscribe(
                    StoreGame::class.java,
                    errorHandler = { dispatcher.schedule { loading.removeAll(requested.toSet()) } },
                ) { response -> dispatcher.schedule {
                    loading.removeAll(requested.toSet())
                    response.forEach { games[it.id] = it }
                    markChanged()
                } }
        }
    }

    fun observeGames(gameIds: Collection<Long>): Observable<Map<Long, Game>> =
        observationDeck.connectRx(this) { snapshot.filterKeys(gameIds::contains) }.distinctUntilChanged()

    @StoreThread
    internal fun handlePreLogout() {
        games.clear()
        loading.clear()
        markChanged()
    }

    override fun snapshotData() {
        snapshot = HashMap(games)
    }

    private companion object {
        const val MAX_GAMES_PER_REQUEST = 20
    }
}
