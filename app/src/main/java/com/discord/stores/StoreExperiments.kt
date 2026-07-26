package com.discord.stores

import android.content.Context
import com.discord.models.domain.ModelPayload
import com.discord.models.experiments.domain.Experiment
import com.discord.models.experiments.domain.ExperimentHash
import com.discord.models.experiments.dto.GuildExperimentDto
import com.discord.models.experiments.dto.GuildExperimentFilter
import com.discord.models.experiments.dto.UserExperimentDto
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import com.discord.utilities.user.UserUtils.isStaff
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Evaluates the A/B assignments the backend ships in `READY`, so experiment-gated behaviour
 * resolves the same way it does in the reference client.
 *
 * Two kinds of assignment exist and they resolve very differently:
 *
 * - **User experiments** arrive pre-bucketed — the server already decided, and the client only
 *   looks the bucket up by name hash.
 * - **Guild experiments** arrive as a *rule set*; the bucket is computed locally from the guild id,
 *   the guild's member count and features, and the experiment's population filters. This is why
 *   [StoreGuilds] and the member-count source are dependencies.
 *
 * Exposure analytics are deliberately not fired on read. [Experiment.trackExposure] is invoked by
 * the caller at the moment the assignment actually changes what the user sees, and is then
 * de-duplicated for a week per experiment so a screen that re-reads on every bind does not spam.
 */
class StoreExperiments(
    private val clock: Clock,
    private val dispatcher: Dispatcher,
    private val storeUser: StoreUser,
    private val storeGuilds: StoreGuilds,
    private val storeAuthentication: StoreAuthentication,
    /**
     * `StoreGuildMemberCounts.getApproximateMemberCount`. Not yet reconstructed; until it exists
     * every `guild_member_count_range` filter evaluates against 0 and those populations never
     * match, which fails closed rather than enrolling users incorrectly.
     */
    private val approximateMemberCount: (Long) -> Int = { 0 },
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val api: () -> RestAPIInterface? = { null },
    private val trackUserExperimentTriggered: (name: String, revision: Int, population: Int, bucket: Int) -> Unit = { _, _, _, _ -> },
    private val trackGuildExperimentTriggered: (name: String, revision: Int, bucket: Int, guildId: Long) -> Unit = { _, _, _, _ -> },
) : StoreV2() {
    /**
     * `null` means "logged out"; [UNINITIALIZED] means "not told yet". The distinction matters
     * because the unauthenticated experiment fetch must run exactly once we know we are logged
     * out, not while we are still restoring the cached auth state.
     */
    private var authToken: String? = UNINITIALIZED
    private var fingerprint: String? = UNINITIALIZED
    private var initialized = false

    private val userExperiments = HashMap<Long, UserExperimentDto>()
    private val guildExperiments = HashMap<Long, GuildExperimentDto>()
    private val experimentOverrides = HashMap<String, Int>()
    private val memoizedGuildExperiments = HashMap<String, Experiment>()
    private val experimentTrackedExposureTimestamps = HashMap<String, Long>()

    private var userExperimentsSnapshot: Map<Long, UserExperimentDto> = emptyMap()
    private var guildExperimentsSnapshot: Map<Long, GuildExperimentDto> = emptyMap()
    private var experimentOverridesSnapshot: Map<String, Int> = emptyMap()

    private val userExperimentsCache =
        Persister<Map<Long, UserExperimentDto>>(USER_EXPERIMENTS_CACHE_KEY, HashMap())
    private val guildExperimentsCache =
        Persister<List<GuildExperimentDto>>(GUILD_EXPERIMENTS_CACHE_KEY, ArrayList())
    private val experimentOverridesCache =
        Persister<Map<String, Int>>(EXPERIMENT_OVERRIDES_CACHE_KEY, HashMap())

    override fun init(context: Context) {
        super.init(context)
        experimentTrackedExposureTimestamps.putAll(loadCachedExperimentTrackedExposureTimestamps())
        experimentOverrides.putAll(experimentOverridesCache.get())
        handleLoadedUserExperiments(userExperimentsCache.get(), doCache = false)
        handleLoadedGuildExperiments(guildExperimentsCache.get(), doCache = false)
        markChanged(ExperimentOverridesUpdateSource)
    }

    // region gateway / auth input

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        handleLoadedUserExperiments(payload.experiments.orEmpty(), doCache = true)
        payload.guildExperiments?.let { handleLoadedGuildExperiments(it, doCache = true) }
    }

    @StoreThread
    fun handleAuthToken(authToken: String?) {
        this.authToken = authToken
        reset()
        tryInitializeExperiments()
    }

    @StoreThread
    fun handleFingerprint(fingerprint: String?) {
        this.fingerprint = fingerprint
        tryInitializeExperiments()
    }

    // endregion

    // region reads

    /**
     * @param trackExposure fire the exposure event immediately. Pass false when merely inspecting
     *   the assignment (a debug screen, a log line) so the analytics population stays honest.
     */
    @JvmOverloads
    fun getUserExperiment(name: String, trackExposure: Boolean = false): Experiment? {
        val nameHash = ExperimentHash.from(name)
        val dto = userExperimentsSnapshot[nameHash]
        experimentOverridesSnapshot[name]?.let { override ->
            return Experiment(dto?.revision ?: 0, override, dto?.population ?: 0, isOverride = true) {}
        }
        if (dto == null) return null
        val experiment = Experiment(dto.revision, dto.bucket, dto.population, isOverride = false) {
            trackExposureToUserExperiment(name, dto.bucket, dto.population, dto.revision)
        }
        if (trackExposure) experiment.trackExposure()
        return experiment
    }

    @JvmOverloads
    fun getGuildExperiment(experimentName: String, guildId: Long, trackExposure: Boolean = false): Experiment? {
        val dto = guildExperimentsSnapshot[ExperimentHash.from(experimentName)]
        experimentOverridesSnapshot[experimentName]?.let { override ->
            return Experiment(dto?.revision ?: 0, override, 0, isOverride = true) {}
        }
        // Reference bug reproduced deliberately: the lookup passes "<guildId>:<name>" as the name,
        // so the key becomes "<guildId>:<name>:<guildId>" while `memoizeGuildExperiment` stores
        // "<name>:<guildId>" — the memo never hits and every read recomputes. Repairing it would
        // freeze a bucket computed before the guild's member count was known, which is a real
        // behaviour change, so it stays as shipped.
        getMemoizedGuildExperiment("$guildId:$experimentName", guildId)?.let { return it }
        if (dto == null) return null
        val bucket = computeGuildExperimentBucket(
            experimentName,
            guildId,
            approximateMemberCount(guildId),
            storeGuilds.getGuild(guildId),
            dto,
        )
        val experiment = Experiment(dto.revision, bucket, 0, isOverride = false) {
            trackExposureToGuildExperiment(experimentName, dto.revision, bucket, guildId)
        }
        if (trackExposure) experiment.trackExposure()
        memoizeGuildExperiment(experimentName, guildId, experiment)
        return experiment
    }

    @JvmOverloads
    fun observeUserExperiment(name: String, trackExposure: Boolean = false): Observable<Experiment?> =
        observationDeck.connectRx(this) { getUserExperiment(name, trackExposure) }.distinctUntilChanged()

    @JvmOverloads
    fun observeGuildExperiment(
        experimentName: String,
        guildId: Long,
        trackExposure: Boolean = false,
    ): Observable<Experiment?> =
        observationDeck.connectRx(this) { getGuildExperiment(experimentName, guildId, trackExposure) }

    fun observeOverrides(): Observable<Map<String, Int>> =
        observationDeck.connectRx(ExperimentOverridesUpdateSource) { experimentOverrides.toMap() }

    /** True once the client knows its assignments, authed or not; gates the first render. */
    fun isInitialized(): Observable<Boolean> =
        observationDeck.connectRx(InitializedUpdateSource) { initialized }

    fun getExperimentalAlpha(): Observable<Boolean> =
        observationDeck.connectRx(storeUser, storeGuilds) { isExperimentalAlpha(storeGuilds, storeUser) }

    // endregion

    // region developer overrides

    fun setOverride(experimentName: String, bucket: Int) = dispatcher.schedule {
        if (experimentOverrides[experimentName] != bucket) handleSetOverride(experimentName, bucket)
    }

    fun clearOverride(experimentName: String) = dispatcher.schedule {
        handleClearOverride(experimentName)
    }

    // endregion

    override fun snapshotData() {
        userExperimentsSnapshot = HashMap(userExperiments)
        guildExperimentsSnapshot = HashMap(guildExperiments)
        if (ExperimentOverridesUpdateSource in updateSources) {
            experimentOverridesSnapshot = HashMap(experimentOverrides)
            experimentOverridesCache.set(HashMap(experimentOverrides), persist = true)
        }
    }

    // region internals

    @StoreThread
    private fun handleLoadedUserExperiments(experiments: Map<Long, UserExperimentDto>, doCache: Boolean) {
        userExperiments.clear()
        userExperiments.putAll(experiments)
        if (doCache) userExperimentsCache.set(HashMap(userExperiments), persist = true)
        markChanged()
    }

    @StoreThread
    private fun handleLoadedGuildExperiments(experiments: Collection<GuildExperimentDto>, doCache: Boolean) {
        guildExperiments.clear()
        memoizedGuildExperiments.clear()
        experiments.forEach { guildExperiments[it.experimentIdHash] = it }
        if (doCache) guildExperimentsCache.set(ArrayList(guildExperiments.values), persist = true)
        markChanged()
    }

    @StoreThread
    private fun handleSetOverride(experimentName: String, bucket: Int) {
        experimentOverrides[experimentName] = bucket
        markChanged(ExperimentOverridesUpdateSource)
    }

    @StoreThread
    private fun handleClearOverride(experimentName: String) {
        experimentOverrides.remove(experimentName)
        markChanged(ExperimentOverridesUpdateSource)
    }

    @StoreThread
    private fun reset() {
        if (authToken != null) return
        userExperiments.clear()
        guildExperiments.clear()
        userExperimentsCache.set(HashMap(), persist = true)
        guildExperimentsCache.set(ArrayList(), persist = true)
        initialized = false
        markChanged()
    }

    @StoreThread
    private fun setInitialized() {
        initialized = true
        markChanged(InitializedUpdateSource)
    }

    /**
     * Runs once both the auth token and the fingerprint are known. An authed client already has
     * its assignments from `READY`; a logged-out one must ask `GET /experiments`, which also mints
     * the fingerprint that identifies it for bucketing. The 2s timeout is the reference's: the
     * landing screen must not wait on this.
     */
    @StoreThread
    private fun tryInitializeExperiments() {
        if (initialized || authToken == UNINITIALIZED || fingerprint == UNINITIALIZED) return
        initialized = true
        if (authToken != null) {
            setInitialized()
            return
        }
        val api = api()
        if (api == null) {
            setInitialized()
            return
        }
        api.experiments
            .restSubscribeOn()
            .timeout(UNAUTHENTICATED_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .appSubscribe(
                errorClass = StoreExperiments::class.java,
                errorHandler = { dispatcher.schedule { setInitialized() } },
            ) { response ->
                dispatcher.schedule {
                    storeAuthentication.setFingerprint(response.fingerprint, false)
                    handleLoadedUserExperiments(
                        response.assignments.associateBy(UserExperimentDto::nameHash),
                        doCache = true,
                    )
                    setInitialized()
                }
            }
    }

    @Synchronized
    private fun memoizeGuildExperiment(experimentName: String, guildId: Long, experiment: Experiment) {
        memoizedGuildExperiments["$experimentName:$guildId"] = experiment
    }

    @Synchronized
    internal fun getMemoizedGuildExperiment(experimentName: String, guildId: Long): Experiment? =
        memoizedGuildExperiments["$experimentName:$guildId"]

    @Synchronized
    private fun trackExposureToUserExperiment(name: String, bucket: Int, population: Int, revision: Int) {
        if (wasExperimentExposureTrackedRecently(name, clock.currentTimeMillis())) return
        trackUserExperimentTriggered(name, revision, population, bucket)
        didTrackExposureToExperiment(name)
    }

    @Synchronized
    private fun trackExposureToGuildExperiment(name: String, revision: Int, bucket: Int, guildId: Long) {
        val exposureKey = "$name:$guildId"
        if (wasExperimentExposureTrackedRecently(exposureKey, clock.currentTimeMillis())) return
        trackGuildExperimentTriggered(name, revision, bucket, guildId)
        didTrackExposureToExperiment(exposureKey)
    }

    private fun wasExperimentExposureTrackedRecently(exposureKey: String, nowMs: Long): Boolean =
        nowMs - EXPOSURE_DEDUPE_WINDOW_MS < (experimentTrackedExposureTimestamps[exposureKey] ?: Long.MIN_VALUE)

    private fun didTrackExposureToExperiment(exposureKey: String) {
        experimentTrackedExposureTimestamps[exposureKey] = clock.currentTimeMillis()
        cacheExperimentTrackedExposureTimestamps()
    }

    private fun cacheExperimentTrackedExposureTimestamps() {
        prefs.edit()
            .putString(
                EXPERIMENT_TRACKED_EXPOSURE_TIMESTAMPS_CACHE_KEY,
                Gson().toJson(experimentTrackedExposureTimestamps),
            )
            .apply()
    }

    private fun loadCachedExperimentTrackedExposureTimestamps(): Map<String, Long> {
        val json = prefs.getString(EXPERIMENT_TRACKED_EXPOSURE_TIMESTAMPS_CACHE_KEY, null) ?: return emptyMap()
        return runCatching {
            Gson().fromJson<Map<String, Long>>(json, object : TypeToken<Map<String, Long>>() {}.type)
        }.getOrNull().orEmpty()
    }

    // endregion

    companion object {
        private const val UNINITIALIZED = "UNINITIALIZED"
        private const val DISCORD_TESTERS_GUILD_ID = 197038439483310086L
        private const val USER_EXPERIMENTS_CACHE_KEY = "USER_EXPERIMENTS_CACHE_KEY"
        private const val GUILD_EXPERIMENTS_CACHE_KEY = "GUILD_EXPERIMENTS_CACHE_KEY"
        private const val EXPERIMENT_OVERRIDES_CACHE_KEY = "EXPERIMENT_OVERRIDES_CACHE_KEY"
        private const val EXPERIMENT_TRACKED_EXPOSURE_TIMESTAMPS_CACHE_KEY =
            "EXPERIMENT_TRIGGER_TIMESTAMPS_CACHE_KEY"
        private const val EXPOSURE_DEDUPE_WINDOW_MS = 604_800_000L
        private const val UNAUTHENTICATED_FETCH_TIMEOUT_MS = 2_000L

        /** No population matched, i.e. the guild is not enrolled in the experiment at all. */
        const val BUCKET_NOT_ELIGIBLE = -1

        private const val BUCKET_POSITION_SPACE = 10_000L

        @JvmField val ExperimentOverridesUpdateSource = object : ObservationDeck.UpdateSource {}
        @JvmField val InitializedUpdateSource = object : ObservationDeck.UpdateSource {}

        fun isExperimentalAlpha(storeGuilds: StoreGuilds, storeUser: StoreUser): Boolean =
            storeUser.getMe().isStaff || storeGuilds.getGuilds()[DISCORD_TESTERS_GUILD_ID] != null

        /**
         * Deterministically buckets [guildId] into one of [experiment]'s populations.
         *
         * The position is `murmur("<hashKey or name>:<guildId>") % 10000`; a guild lands in the
         * first bucket whose position ranges contain it, but only within a population whose
         * filters the guild satisfies. Explicit per-guild overrides win outright.
         *
         * TODO(utilities): the reference keeps this in `utilities/experiments/ExperimentUtils`.
         *   It lives here because that package does not exist yet and is owned by another wave.
         */
        internal fun computeGuildExperimentBucket(
            experimentName: String,
            guildId: Long,
            guildMemberCount: Int,
            guild: Guild?,
            experiment: GuildExperimentDto,
        ): Int {
            experiment.overrides.firstOrNull { guildId in it.guilds }?.let { return it.bucket }

            val position =
                ExperimentHash.from("${experiment.hashKey ?: experimentName}:$guildId") % BUCKET_POSITION_SPACE

            for (population in experiment.populations) {
                val matches = population.filters.all { filter ->
                    when (filter) {
                        is GuildExperimentFilter.GuildIdsFilter -> guildId in filter.guildIds
                        is GuildExperimentFilter.GuildIdRangeFilter -> guildId in filter.range
                        is GuildExperimentFilter.GuildMemberCountRangeFilter ->
                            guildMemberCount.toLong() in filter.range
                        is GuildExperimentFilter.GuildHasFeatureFilter ->
                            filter.features.any { guild?.hasFeature(it) == true }
                        is GuildExperimentFilter.GuildHubTypesFeatureFilter ->
                            filter.hubTypes.any { guild?.hubType == it }
                    }
                }
                if (!matches) continue
                // The reference's range test is half-open at the top: `first <= p && p < last`.
                val bucket = population.buckets.firstOrNull { candidate ->
                    candidate.positions.any { position >= it.first && position < it.last }
                }
                return bucket?.bucket ?: 0
            }
            return BUCKET_NOT_ELIGIBLE
        }
    }
}
