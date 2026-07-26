package com.discord.stores

import android.content.SharedPreferences
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.api.user.SurveyGuildRequirements
import com.discord.api.user.UserSurvey
import com.discord.api.user.UserSurveyFetchResponse
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import rx.Observable

/**
 * The set of `guild_requirements` this client knows how to evaluate.
 *
 * A survey that asks for anything outside this set is discarded rather than shown, so a
 * server-side requirement added after 126.21 can never be silently treated as satisfied.
 * `GUILD_PERMISSIONS` is deliberately absent — it is evaluated below but is not an accepted
 * requirement, so in practice a permissions-gated survey never displays. That is what the
 * reference does.
 */
internal val IMPLEMENTED_GUILD_REQUIREMENTS: Set<String> =
    SurveyGuildRequirements.entries.take(5).map { it.value }.toSet()

/**
 * The in-product research prompt shown at most once a day.
 *
 * The server decides *what* to ask; this store decides *whether* the account is eligible, because
 * the requirements are expressed over guilds the client already has and re-asking the server for
 * each would be a round trip per guild.
 */
class StoreUserSurvey(
    private val dispatcher: Dispatcher,
    private val storeUser: StoreUser,
    private val storeGuilds: StoreGuilds,
    private val storePermissions: StorePermissions,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val api: () -> RestAPIInterface? = { null },
    private val clock: Clock,
    private val sharedPreferences: SharedPreferences,
) : StoreV2() {
    private var userSurvey: UserSurvey? = null
    private val refetchIntervalMs = REFETCH_INTERVAL_MS

    fun getUserSurvey(): UserSurvey? = userSurvey

    fun observeUserSurvey(): Observable<UserSurvey?> =
        observationDeck.connectRx(this) { getUserSurvey() }

    @StoreThread
    fun handleConnectionOpen() {
        val lastFetched = sharedPreferences.getLong(CACHE_KEY_LAST_FETCHED, 0L)
        if (clock.currentTimeMillis() - lastFetched > refetchIntervalMs) fetchUserSurvey()
    }

    @StoreThread
    private fun fetchUserSurvey() {
        setLastFetched()
        val api = api() ?: return
        api.getUserSurvey().restSubscribeOn().appSubscribe(StoreUserSurvey::class.java) { response ->
            dispatcher.schedule { handleUserSurveyFetchSuccess(response.body()) }
        }
    }

    @StoreThread
    internal fun handleUserSurveyFetchSuccess(response: UserSurveyFetchResponse?) {
        val survey = response?.survey ?: return
        if (!meetsGuildRequirements(survey)) return
        userSurvey = survey
        markChanged()
    }

    /**
     * @return true when at least one guild satisfies *every* requirement the survey lists.
     *
     * Requirements are conjunctive within a guild and disjunctive across guilds — "you are an
     * admin of some community server", not "you are an admin somewhere and in a community
     * somewhere".
     */
    internal fun meetsGuildRequirements(survey: UserSurvey): Boolean {
        val requirements = survey.guildRequirements
        if (requirements.isEmpty()) return true
        if (requirements.any { it !in IMPLEMENTED_GUILD_REQUIREMENTS }) return false

        val me = storeUser.getMe()
        val guildPermissions = storePermissions.getGuildPermissions()
        return storeGuilds.getGuilds().any { (guildId, guild) ->
            val permissions = guildPermissions[guildId]
            requirements.all { requirement ->
                when (requirement) {
                    SurveyGuildRequirements.IS_COMMUNITY.value ->
                        guild.hasFeature(GuildFeature.COMMUNITY)
                    SurveyGuildRequirements.IS_HUB.value ->
                        guild.hasFeature(GuildFeature.HUB)
                    SurveyGuildRequirements.GUILD_SIZE.value ->
                        guild.memberCount >= (survey.guildSize.getOrNull(0) ?: Int.MIN_VALUE) &&
                            guild.memberCount <= (survey.guildSize.getOrNull(1) ?: Int.MAX_VALUE)
                    SurveyGuildRequirements.GUILD_PERMISSIONS.value ->
                        survey.guildPermissions.isEmpty() ||
                            survey.guildPermissions.any { PermissionUtils.can(it, permissions) }
                    SurveyGuildRequirements.IS_OWNER.value -> guild.isOwner(me.id)
                    SurveyGuildRequirements.IS_ADMIN.value ->
                        PermissionUtils.can(Permission.ADMINISTRATOR, permissions)
                    else -> false
                }
            }
        }
    }

    private fun setLastFetched() {
        sharedPreferences.edit().putLong(CACHE_KEY_LAST_FETCHED, clock.currentTimeMillis()).apply()
    }

    companion object {
        private const val CACHE_KEY_LAST_FETCHED = "CACHE_KEY_USER_SURVEY_LAST_FETCHED"
        private const val REFETCH_INTERVAL_MS = 86_400_000L
    }
}
