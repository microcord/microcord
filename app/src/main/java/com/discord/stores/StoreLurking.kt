package com.discord.stores

import com.discord.api.guild.Guild as ApiGuild
import com.discord.api.guild.welcome.GuildWelcomeScreen
import com.discord.app.AppLog
import com.discord.models.domain.ModelPayload
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rest.contextPropertiesOf
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Guilds the user is *previewing* rather than a member of.
 *
 * Lurking is how a discoverable server is opened from an invite, from Discovery, or from a deep
 * link without joining it: the client calls `PUT /guilds/{id}/members/@me?lurker=true`, which
 * makes the gateway stream that guild for the current session only. A lurked guild therefore:
 *
 * * has no `joined_at` on its member row — that is exactly how [isLurking] recognises one,
 * * is never written to any persister ([StoreGuilds], [StoreGuildSelected], [StoreChannels] all
 *   subtract [getLurkingGuildIdsSync] before caching), and
 * * is dropped as soon as the user navigates away, unless they are in one of its voice channels.
 *
 * This is a plain [Store], not a [StoreV2]: the reference publishes through a subject rather than
 * the observation deck, because lurk state changes are driven by REST replies rather than by the
 * dispatch cycle.
 */
class StoreLurking(
    private val guildsStore: StoreGuilds,
    private val guildSelectedStore: StoreGuildSelected,
    private val channelsStore: StoreChannels,
    private val dispatcher: Dispatcher,
    /** Resolves the voice channel the user is connected to, so its guild is never un-lurked. */
    private val getSelectedVoiceChannelId: () -> Long = { 0L },
    /** Selects a channel after a lurk succeeds. Wire to `StoreStream.selectChannel`. */
    private val selectChannel: (guildId: Long, channelId: Long) -> Unit = { _, _ -> },
    /** Selects a guild when the lurk has no channel to open. Wire to `StoreGuildSelected::set`. */
    private val selectGuild: (guildId: Long) -> Unit = {},
    /** Runs the post-join flow (welcome screen, member verification). */
    private val onGuildJoined: (guildId: Long, welcomeScreen: GuildWelcomeScreen?) -> Unit = { _, _ -> },
    /** Raises the "you are just looking around" notice. Wire to `StoreNavigation.launchNotice`. */
    private val launchLurkNotice: (guildId: Long) -> Unit = {},
) : Store() {

    /** Why a guild is being lurked. [shouldNavigate] survives until the guild actually arrives. */
    private data class LurkContext(val guildId: Long, val shouldNavigate: Boolean)

    /** A lurk asked for before the gateway session existed; replayed on the next READY. */
    private data class LurkRequest(val guildId: Long, val channelId: Long? = null, val source: String? = null)

    private val lurkedGuilds = LinkedHashMap<Long, LurkContext>()
    private val lurkedGuildsSubject = BehaviorSubject.create<Map<Long, LurkContext>>(lurkedGuilds)
    private var lurkRequest: LurkRequest? = null

    /** The gateway session id; lurking is session-scoped and impossible without one. */
    private var sessionId: String? = null

    private var api: RestAPIInterface? = null

    fun bindApi(api: RestAPIInterface) {
        this.api = api
    }

    fun getLurkingGuildIds(): Observable<Set<Long>> = lurkedGuildsSubject.map { it.keys.toSet() }

    fun getLurkingGuildIdsSync(): Set<Long> = lurkedGuilds.keys.toSet()

    /** Emits whether [guildId] is lurked, re-checking whenever the guild itself changes. */
    fun isLurkingObs(guildId: Long): Observable<Boolean> =
        Observable.combineLatest(
            getLurkingGuildIds(),
            guildsStore.observeGuild(guildId),
        ) { lurkingIds, guild -> isLurking(guild?.joinedAt != null, guildId, lurkingIds) }
            .distinctUntilChanged()

    @StoreThread
    internal fun isLurking(guildId: Long): Boolean = isLurking(false, guildId, lurkedGuilds.keys)

    @StoreThread
    internal fun isLurking(guild: ApiGuild): Boolean =
        isLurking(guild.joinedAt != null, guild.id, lurkedGuilds.keys)

    @StoreThread
    internal fun isLurking(guild: Guild): Boolean =
        isLurking(guild.joinedAt != null, guild.id, lurkedGuilds.keys)

    @StoreThread
    internal fun handleConnectionOpen(payload: ModelPayload) {
        sessionId = payload.sessionId
        val readyLurkIds = payload.guilds.orEmpty()
            .filter { it.joinedAt == null && !it.unavailable }
            .mapTo(LinkedHashSet(), ApiGuild::id)
        if (lurkedGuilds.keys != readyLurkIds) {
            lurkedGuilds.clear()
            readyLurkIds.forEach { lurkedGuilds[it] = LurkContext(it, shouldNavigate = false) }
            lurkedGuildsSubject.onNext(lurkedGuilds)
        }
        val queued = lurkRequest
        if (queued != null) {
            AppLog.i("Processing lurk request from app link")
            startLurkingAndNavigate(queued.guildId, queued.channelId, queued.source)
        }
        lurkRequest = null
    }

    /**
     * A lurked guild arriving over the gateway either completes the navigation it was requested
     * for, or — if it now carries a `joined_at` — means the user really joined and is no longer
     * lurking.
     */
    @StoreThread
    internal fun handleGuildAdd(guild: ApiGuild) {
        if (guild.id !in lurkedGuilds) return
        if (guild.joinedAt == null) {
            if (lurkedGuilds[guild.id]?.shouldNavigate == true) {
                guildSelectedStore.handleGuildSelected(guild.id)
                return
            }
        }
        if (guild.joinedAt != null) stopLurkingInternal(guild.id)
    }

    @StoreThread
    internal fun handleGuildRemove(guild: ApiGuild) = stopLurkingInternal(guild.id)

    @StoreThread
    internal fun handleCurrentUserJoined(guildId: Long) = stopLurkingInternal(guildId)

    @StoreThread
    internal fun handleGuildSelected(guildId: Long) {
        if ((lurkRequest == null || guildId != lurkRequest?.guildId) && sessionId != null) {
            lurkRequest = null
        }
        removeOldLurkingGuilds(guildId)
    }

    /**
     * Leaves every lurked guild except the one whose voice channel was just selected. Staying in
     * a lurked guild's voice channel is the one case where the preview outlives navigation.
     */
    @StoreThread
    internal fun handleVoiceChannelSelected(channelId: Long) {
        val channel = channelsStore.getChannel(channelId)
        lurkedGuilds.keys
            .filter { channel == null || it != channel.guildId }
            .filter { it != guildSelectedStore.getSelectedGuildIdInternal() }
            .forEach { postLeaveGuild(it) }
    }

    /**
     * Leaves every lurked guild other than [currentlyLurkedGuildId] and the guild backing the
     * active voice connection.
     */
    fun removeOldLurkingGuilds(currentlyLurkedGuildId: Long) {
        val voiceGuildId = channelsStore.getChannel(getSelectedVoiceChannelId())?.guildId
        lurkedGuilds.keys
            .filter { it != currentlyLurkedGuildId && (voiceGuildId == null || it != voiceGuildId) }
            .mapNotNull { guildsStore.getGuildsInternal()[it] }
            .forEach { postLeaveGuild(it.id) }
    }

    /**
     * Starts previewing [guildId]. [onGuildLurked] receives the guild, the channel to open, and
     * whether the user was already a member — the caller decides what to navigate to.
     */
    @JvmOverloads
    fun startLurking(
        guildId: Long,
        channelId: Long?,
        navigateToGuild: Boolean = true,
        onGuildLurked: (ApiGuild?, Long?, Boolean) -> Unit = { _, _, _ -> },
        onErrorLurking: () -> Unit = {},
    ) = dispatcher.schedule {
        startLurkingInternal(guildId, channelId, null, navigateToGuild, onGuildLurked, onErrorLurking)
    }

    /**
     * Starts previewing [guildId] and opens it. When there is no session yet the request is
     * queued and replayed from [handleConnectionOpen], which is what makes a cold-start deep
     * link into a lurk work.
     */
    fun startLurkingAndNavigate(guildId: Long, channelId: Long?, source: String? = null) = dispatcher.schedule {
        if (sessionId == null) {
            AppLog.i("Queue lurk request: $guildId:$channelId")
            lurkRequest = LurkRequest(guildId, channelId, source)
            return@schedule
        }
        if (isLurking(guildId)) {
            navigate(guildId, channelId)
            return@schedule
        }
        startLurkingInternal(
            guildId = guildId,
            channelId = channelId,
            source = source,
            navigateToGuild = false,
            onGuildLurked = { guild, resolvedChannelId, alreadyJoined ->
                navigate(guildId, resolvedChannelId)
                if (!alreadyJoined) {
                    onGuildJoined(guildId, guild?.welcomeScreen)
                    launchLurkNotice(guildId)
                }
            },
        )
    }

    fun stopLurking(guildId: Long, onSuccess: () -> Unit = {}, onFailure: () -> Unit = {}) =
        postLeaveGuild(guildId, onSuccess, onFailure)

    /** Converts an active preview into a real membership. */
    fun postJoinGuildAsMember(
        guildId: Long,
        onSuccess: (ApiGuild) -> Unit = {},
        onFailure: () -> Unit = {},
        source: String? = null,
    ) {
        val api = api ?: return onFailure()
        api.joinGuild(
            guildId,
            false,
            sessionId,
            null,
            RestAPIParams.InviteCode(),
            contextPropertiesOf("source" to source),
        )
            .restSubscribeOn()
            .ui()
            .appSubscribe(
                StoreLurking::class.java,
                errorHandler = { onFailure() },
            ) { guild -> onSuccess(guild) }
    }

    @StoreThread
    private fun startLurkingInternal(
        guildId: Long,
        channelId: Long?,
        source: String? = null,
        navigateToGuild: Boolean = false,
        onGuildLurked: (ApiGuild?, Long?, Boolean) -> Unit = { _, _, _ -> },
        onErrorLurking: () -> Unit = {},
    ) {
        if (sessionId == null) {
            AppLog.w("Cannot lurk $guildId:$channelId with no session")
            onErrorLurking()
            return
        }
        if (guildId == 0L) {
            AppLog.w("Invalid ids for lurking $guildId:$channelId")
            onErrorLurking()
            return
        }
        val known = guildsStore.getGuildsInternal()[guildId]
        if (known != null && !isLurking(known)) {
            // Already a member: nothing to request, the caller just navigates.
            onGuildLurked(null, channelId, true)
            return
        }

        lurkedGuilds[guildId] = LurkContext(guildId, navigateToGuild)
        lurkedGuildsSubject.onNext(lurkedGuilds)

        val api = api ?: return onErrorLurking()
        api.joinGuild(guildId, true, sessionId, null, RestAPIParams.InviteCode(), contextPropertiesOf("source" to source))
            .restSubscribeOn()
            .ui()
            .appSubscribe(
                StoreLurking::class.java,
                errorHandler = {
                    dispatcher.schedule {
                        lurkedGuilds.remove(guildId)
                        lurkedGuildsSubject.onNext(lurkedGuilds)
                        onErrorLurking()
                    }
                },
            ) { guild ->
                dispatcher.schedule {
                    guildsStore.handleGuildAdd(guild)
                    channelsStore.handleGuildAdd(guild)
                    if (!isLurking(guild)) {
                        lurkedGuilds.remove(guildId)
                        lurkedGuildsSubject.onNext(lurkedGuilds)
                    }
                    onGuildLurked(guild, channelId, false)
                }
            }
    }

    @StoreThread
    private fun stopLurkingInternal(guildId: Long) {
        if (lurkedGuilds.remove(guildId) != null) lurkedGuildsSubject.onNext(lurkedGuilds)
    }

    /**
     * `DELETE /users/@me/guilds/{id}` with `lurking = true`, which tells the server this was a
     * preview and not a real departure.
     */
    private fun postLeaveGuild(guildId: Long, onSuccess: () -> Unit = {}, onFailure: () -> Unit = {}) {
        val api = api ?: return onFailure()
        api.leaveGuild(guildId, RestAPIParams.LeaveGuildBody(true))
            .restSubscribeOn()
            .appSubscribe(
                StoreLurking::class.java,
                errorHandler = { onFailure() },
            ) { onSuccess() }
    }

    private fun navigate(guildId: Long, channelId: Long?) {
        if (channelId != null && channelId != 0L && channelId != -1L) {
            selectChannel(guildId, channelId)
        } else {
            selectGuild(guildId)
        }
    }

    companion object {
        /**
         * A guild is lurked when it is in the lurk set *and* the user is not actually a member.
         * The membership test is `joined_at != null`, which the caller resolves first because
         * the two guild types (API and model) spell it differently.
         */
        @JvmStatic
        fun isLurking(isJoined: Boolean, guildId: Long, lurkingGuildIds: Set<Long>): Boolean =
            !isJoined && lurkingGuildIds.contains(guildId)
    }
}
