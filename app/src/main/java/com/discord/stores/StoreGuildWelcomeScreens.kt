package com.discord.stores

import com.discord.api.guild.welcome.GuildWelcomeScreen
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The onboarding screen a community guild shows the first time it is opened.
 *
 * Two things are tracked, and they are deliberately separate. [guildWelcomeScreensState] is the
 * fetched content, which can also arrive for free on the `GUILD_CREATE` that follows a join
 * ([handleGuildJoined]) — that path skips the REST call entirely. [guildWelcomeScreensSeen] is
 * whether this session already displayed it, so it does not reappear on every navigation.
 *
 * "Seen" is session state, not device state: the reference never persists it.
 */
class StoreGuildWelcomeScreens(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    sealed class State {
        object Fetching : State()
        object Failure : State()
        data class Loaded(val data: GuildWelcomeScreen?) : State()
    }

    private val guildWelcomeScreensState = HashMap<Long, State>()
    private var guildWelcomeScreensSnapshot: Map<Long, State> = emptyMap()
    private val guildWelcomeScreensSeen = HashSet<Long>()
    private var guildWelcomeScreensSeenSnapshot: Set<Long> = emptySet()

    private var api: RestAPIInterface? = null

    fun bindApi(api: RestAPIInterface) {
        this.api = api
    }

    fun getGuildWelcomeScreen(guildId: Long): State? = guildWelcomeScreensSnapshot[guildId]

    fun hasWelcomeScreenBeenSeen(guildId: Long): Boolean = guildId in guildWelcomeScreensSeenSnapshot

    fun observeGuildWelcomeScreen(guildId: Long): Observable<State?> =
        observationDeck.connectRx(this) { getGuildWelcomeScreen(guildId) }.distinctUntilChanged()

    /**
     * Fetches once per guild. Unlike [StoreGuildProfiles] a failure is *not* retried, because the
     * common failure here is "this guild has no welcome screen" rather than a transient error.
     */
    fun fetchIfNonexisting(guildId: Long) = dispatcher.schedule {
        val state = guildWelcomeScreensState[guildId]
        if (state is State.Loaded || state is State.Fetching) return@schedule
        val api = api ?: return@schedule
        handleGuildWelcomeScreenFetchStart(guildId)
        api.getGuildWelcomeScreen(guildId)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGuildWelcomeScreens::class.java,
                errorHandler = { dispatcher.schedule { handleGuildWelcomeScreenFetchFailed(guildId) } },
            ) { screen -> dispatcher.schedule { handleGuildWelcomeScreen(guildId, screen) } }
    }

    /** The welcome screen that came attached to the guild the user just joined. */
    fun handleGuildJoined(guildId: Long, guildWelcomeScreen: GuildWelcomeScreen?) =
        handleGuildWelcomeScreen(guildId, guildWelcomeScreen)

    fun markWelcomeScreenShown(guildId: Long) = dispatcher.schedule {
        if (guildWelcomeScreensSeen.add(guildId)) markChanged()
    }

    @StoreThread
    internal fun handleGuildWelcomeScreen(guildId: Long, guildWelcomeScreen: GuildWelcomeScreen?) {
        guildWelcomeScreensState[guildId] = State.Loaded(guildWelcomeScreen)
        markChanged()
    }

    @StoreThread
    internal fun handleGuildWelcomeScreenFetchStart(guildId: Long) {
        guildWelcomeScreensState[guildId] = State.Fetching
        markChanged()
    }

    @StoreThread
    internal fun handleGuildWelcomeScreenFetchFailed(guildId: Long) {
        guildWelcomeScreensState[guildId] = State.Failure
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        guildWelcomeScreensSnapshot = HashMap(guildWelcomeScreensState)
        guildWelcomeScreensSeenSnapshot = HashSet(guildWelcomeScreensSeen)
    }
}
