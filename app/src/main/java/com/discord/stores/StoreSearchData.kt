package com.discord.stores

import com.discord.api.channel.isGuildTextyChannel
import com.discord.stores.StoreSearch.SearchTarget
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.search.validation.SearchData
import com.discord.widgets.search.SearchDataStore
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject

class StoreSearchData(
    private val observationDeck: ObservationDeck,
    private val storeChannels: StoreChannels,
    private val storeUser: StoreUser,
    private val storeGuilds: StoreGuilds,
) : SearchDataStore {
    private val searchDataSubject = BehaviorSubject.create(SearchData())
    private var subscription: Subscription? = null

    override fun get(): Observable<SearchData> = searchDataSubject

    fun init(searchTarget: SearchTarget) {
        val source = when (searchTarget.type) {
            SearchTarget.Type.GUILD -> getGuildSearchData(searchTarget.id)
            SearchTarget.Type.CHANNEL -> getChannelSearchData(searchTarget.id)
            // No guild to enumerate members or channels from, so only `has:`-style filters and free
            // text are suggestible.
            SearchTarget.Type.DMS -> Observable.just(SearchData())
        }
        source.computationBuffered().distinctUntilChanged().appSubscribe(
            errorClass = StoreSearchData::class.java,
            subscriptionHandler = ::handleSubscription,
            onNext = searchDataSubject::onNext,
        )
    }

    override fun init(searchTarget: com.discord.utilities.search.SearchTarget) =
        init(SearchTarget(SearchTarget.Type.valueOf(searchTarget.type.name), searchTarget.id))

    override fun clear() {
        handleSubscription(null)
        searchDataSubject.onNext(SearchData())
    }

    private fun getChannelSearchData(channelId: Long): Observable<SearchData> =
        observationDeck.connectRx(storeChannels, storeUser, storeGuilds) {
            val channel = storeChannels.getChannel(channelId)
            val members = storeGuilds.getMembers()[channel?.guildId ?: 0L].orEmpty()
            SearchData.Builder().buildForChannel(channel, storeUser.getMe(), members)
        }

    private fun getGuildSearchData(guildId: Long): Observable<SearchData> = Observable
        .combineLatest(
            storeGuilds.observeComputed(guildId),
            storeUser.observeAllUsers(),
            storeChannels.observeChannelsForGuild(guildId)
                .map { channels -> channels.filterValues { it.isGuildTextyChannel() } },
            StoreStream.getPermissions().observeChannelPermissionsForGuild(guildId),
        ) { members, users, channels, permissions ->
            SearchData.Builder().buildForGuild(members, users, channels, permissions)
        }
        .leadingEdgeThrottle(GUILD_DATA_THROTTLE_SECONDS, TimeUnit.SECONDS)

    @Synchronized
    private fun handleSubscription(subscription: Subscription?) {
        this.subscription?.unsubscribe()
        this.subscription = subscription
    }

    private companion object {
        const val GUILD_DATA_THROTTLE_SECONDS = 3L
    }
}
