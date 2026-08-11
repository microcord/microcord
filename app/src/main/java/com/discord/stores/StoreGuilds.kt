package com.discord.stores

import android.content.Context
import com.discord.api.guild.Guild as ApiGuild
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildNsfwLevel
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.guildmember.GuildMember as ApiGuildMember
import com.discord.api.guildmember.GuildMembersChunk
import com.discord.api.role.GuildRole
import com.discord.api.thread.ThreadMemberListUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.app.AppComponent
import com.discord.models.domain.ModelPayload
import com.discord.models.guild.GeoRestrictedGuild
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.collections.SnowflakePartitionMap
import com.discord.utilities.guildmember.getJoinedAtOrNow
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import java.util.concurrent.TimeUnit

/**
 * Raw and computed guild state: the guilds themselves, their roles, their members, which of them
 * are currently unavailable, and when the current user joined each one.
 *
 * Members are held twice. [guildMembers] keeps the API payload exactly as it arrived, because a
 * later partial update has to be merged against it; [guildMembersComputed] keeps the resolved
 * [GuildMember] (role colour, hoist role, nickname) that the UI actually renders. The two are
 * published through separate update sources so that a role change does not wake every guild-list
 * observer, and vice versa.
 */
class StoreGuilds(
    private val userStore: StoreUser,
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val guildsCache: Persister<Map<Long, Guild>> = Persister(GUILDS_CACHE_KEY, emptyMap()),
    private val guildRolesCache: Persister<Map<Long, Map<Long, GuildRole>>> =
        Persister(GUILD_ROLES_CACHE_KEY, emptyMap()),
    private val guildsJoinedAtCache: Persister<Map<Long, Long>> =
        Persister(GUILD_JOINED_AT_CACHE_KEY, emptyMap()),
    /**
     * Lurked guilds are never written to the persister: they are not real memberships and must
     * not reappear on the next cold start. Wire to `StoreLurking::getLurkingGuildIdsSync`.
     */
    private val getLurkingGuildIds: () -> Set<Long> = { emptySet() },
    /**
     * Invoked when the current user's own timeout is lifted, so channel permissions can be
     * recomputed. Wire to `StorePermissions::handleGuildMemberAdd`.
     */
    private val onMeCommunicationEnabled: (ApiGuildMember) -> Unit = {},
    /** Drives the timeout-expiry sweep; overridden in tests with a `TestScheduler`. */
    private val communicationDisabledScheduler: Scheduler = Schedulers.computation(),
) : StoreV2() {
    private val guilds = HashMap<Long, Guild>()
    private val guildMembers = HashMap<Long, MutableMap<Long, ApiGuildMember>>()
    private val guildMembersComputed = HashMap<Long, MutableMap<Long, GuildMember>>()
    private val guildRoles = HashMap<Long, MutableMap<Long, GuildRole>>()
    private val guildsUnavailable = HashSet<Long>()
    private val geoRestrictedGuilds = LinkedHashMap<Long, GeoRestrictedGuild>()
    private val guildsJoinedAt = HashMap<Long, Long>()

    private var guildsSnapshot: Map<Long, Guild> = emptyMap()
    private var guildMembersComputedSnapshot: Map<Long, Map<Long, GuildMember>> = emptyMap()
    private var guildRolesSnapshot: Map<Long, Map<Long, GuildRole>> = emptyMap()
    private var guildsUnavailableSnapshot: Set<Long> = emptySet()
    private var geoRestrictedGuildsSnapshot: List<GeoRestrictedGuild> = emptyList()
    private var guildsJoinedAtSnapshot: Map<Long, Long> = emptyMap()

    private var communicationDisabledSubscription: Subscription? = null

    private val meId: Long
        get() = userStore.getMeInternal().id

    override fun init(context: Context) {
        restore()
        initClearCommunicationDisabledObserver(context)
    }

    internal fun restore() {
        guilds.putAll(guildsCache.get())
        guildRolesCache.get().forEach { (guildId, roles) -> guildRoles[guildId] = roles.toMutableMap() }
        guildsJoinedAt.putAll(guildsJoinedAtCache.get())
        markChanged(GuildsUpdate, ComputedMembersUpdate)
    }

    /**
     * Clears expired communication timeouts.
     *
     * A timeout has no gateway event when it lapses — the member simply keeps a
     * `communication_disabled_until` in the past. The client therefore re-evaluates the set of
     * timed-out members every [CLEAR_COMMUNICATION_DISABLED_INTERVAL_SECONDS] seconds and clears
     * the ones whose deadline has passed. Without this, a timed-out member stays muted in the UI
     * until the next full member update.
     */
    internal fun initClearCommunicationDisabledObserver(context: Context?) {
        communicationDisabledSubscription?.unsubscribe()
        communicationDisabledSubscription = null
        Observable.combineLatest(
            observeCommunicationDisabledGuildMembers(),
            Observable.interval(
                0,
                CLEAR_COMMUNICATION_DISABLED_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
                communicationDisabledScheduler,
            ),
        ) { members, _ -> members.filterNot(GuildMember::isCommunicationDisabled) }
            .distinctUntilChanged()
            .computationLatest()
            .appSubscribe(
                StoreGuilds::class.java,
                context,
                subscriptionHandler = { communicationDisabledSubscription = it },
            ) { expired ->
                dispatcher.schedule {
                    expired.forEach { handleGuildMemberCommunicationEnabledInternal(it.guildId, it.userId) }
                }
            }
    }

    override fun snapshotData() {
        if (GuildsUpdate in updateSources) {
            guildsJoinedAtSnapshot = HashMap(guildsJoinedAt)
            guildsJoinedAtCache.set(guildsJoinedAtSnapshot)
            guildsSnapshot = HashMap(guilds)
            // Lurked guilds are excluded from persistence only; the live map still holds them.
            guildsCache.set(guilds - getLurkingGuildIds())
            guildsUnavailableSnapshot = HashSet(guildsUnavailable)
            geoRestrictedGuildsSnapshot = geoRestrictedGuilds.values.toList()
            guildRolesSnapshot = guildRoles.mapValues { HashMap(it.value) }
            guildRolesCache.set(guildRolesSnapshot)
        }
        if (ComputedMembersUpdate in updateSources) {
            val snapshot = HashMap<Long, Map<Long, GuildMember>>(guildMembersComputed.size)
            for (entry in guildMembersComputed.entries) {
                val members = entry.value
                if (members.size < HUGE_GUILD_SIZE) {
                    snapshot[entry.key] = HashMap(members)
                } else {
                    // Copying a 100k-member map on every dispatch is the single most expensive
                    // thing this store does, so large guilds are migrated in place to a
                    // partitioned map that only duplicates the partitions that were written.
                    @Suppress("UNCHECKED_CAST")
                    val partitioned = members as? SnowflakePartitionMap.CopiablePartitionMap<GuildMember>
                        ?: SnowflakePartitionMap.CopiablePartitionMap<GuildMember>()
                            .also { it.putAll(members); entry.setValue(it) }
                    snapshot[entry.key] = partitioned.fastCopy()
                }
            }
            guildMembersComputedSnapshot = snapshot
        }
    }

    fun reset() {
        guilds.clear()
        guildMembers.clear()
        guildMembersComputed.clear()
        guildRoles.clear()
        guildsUnavailable.clear()
        geoRestrictedGuilds.clear()
        guildsJoinedAt.clear()
        guildsCache.clear()
        guildRolesCache.clear()
        guildsJoinedAtCache.clear()
        markChanged(GuildsUpdate, ComputedMembersUpdate)
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        guilds.clear()
        guildMembers.clear()
        guildMembersComputed.clear()
        guildRoles.clear()
        guildsUnavailable.clear()
        geoRestrictedGuilds.clear()
        payload.guilds.orEmpty().forEach { guild ->
            if (guild.geoRestricted) {
                geoRestrictedGuilds[guild.id] = guild.toGeoRestricted()
            } else if (guild.unavailable) {
                guildsUnavailable.add(guild.id)
            } else {
                putRoles(guild.id, guild.roles.orEmpty())
                putGuild(guild)
                val members = guild.members.orEmpty().associateBy { it.user.id }
                members.values.forEach { handleGuildMember(it, guild.id) }
                handleHasRoleAndJoinedAt(guild.id, members)
            }
        }
        markChanged(GuildsUpdate, ComputedMembersUpdate)
    }

    @StoreThread
    fun handleGuildAdd(guild: ApiGuild) {
        // Reachable again: the row goes back to being a normal guild rather than showing twice.
        if (!guild.geoRestricted && geoRestrictedGuilds.remove(guild.id) != null) markChanged(GuildsUpdate)
        handleGuildUnavailable(guild.id, guild.unavailable, remove = false)
        if (guild.unavailable) return
        putRoles(guild.id, guild.roles.orEmpty())
        putGuild(guild)
        val members = guild.members.orEmpty().associateBy { it.user.id }
        members.values.forEach { handleGuildMember(it, guild.id) }
        handleHasRoleAndJoinedAt(guild.id, members)
    }

    @StoreThread
    fun handleGuildRemove(guild: ApiGuild) {
        // A region block arrives as a delete carrying the name and icon, which is all the row needs.
        if (guild.geoRestricted) geoRestrictedGuilds[guild.id] = guild.toGeoRestricted()
        else geoRestrictedGuilds.remove(guild.id)
        handleGuildUnavailable(guild.id, guild.unavailable, remove = true)
        if (guilds.remove(guild.id) != null) markChanged(GuildsUpdate)
        if (guildRoles.remove(guild.id) != null) markChanged(GuildsUpdate)
        // `guildsJoinedAt` is deliberately not pruned: the reference keeps the join time so a
        // guild that is removed and re-added keeps its position in the joined-at ordering.
        if (guildMembers.remove(guild.id) != null) markChanged(GuildsUpdate)
        if (guildMembersComputed.remove(guild.id) != null) markChanged(ComputedMembersUpdate)
    }

    /**
     * Merges one member row into both member maps.
     *
     * [isFullGuildMember] says whether [member] came from a source that carries the whole member
     * object. Partial sources — `GUILD_MEMBER_ADD`, typing events, message authors — omit `bio`
     * and `banner`, so those two fields are carried over from the cached row instead of being
     * overwritten with `null`.
     */
    @StoreThread
    fun handleGuildMember(
        member: ApiGuildMember,
        guildId: Long = member.guildId,
        isFullGuildMember: Boolean = false,
    ) {
        val userId = member.user.id
        val members = guildMembers.getOrPut(guildId) { HashMap() }
        val computedMembers = guildMembersComputed.getOrPut(guildId) { HashMap() }
        val old = members[userId]
        val stored = if (!isFullGuildMember && old != null) {
            member.copy(bio = old.bio, banner = old.banner)
        } else {
            member
        }
        if (stored != old) members[userId] = stored

        val computed = GuildMember.from(stored, guildId, guildRoles[guildId])
        if (computed != computedMembers[userId]) {
            computedMembers[userId] = computed
            markChanged(ComputedMembersUpdate)
        }
    }

    @Deprecated(
        "The flag is inverted; a partial member update preserves the cached profile by default.",
        ReplaceWith("handleGuildMemberAdd(member)"),
    )
    @StoreThread
    fun handleGuildMember(member: ApiGuildMember, preserveProfile: Boolean) =
        handleGuildMember(member, member.guildId, isFullGuildMember = !preserveProfile)

    @StoreThread
    fun handleGuildMemberAdd(member: ApiGuildMember) = handleGuildMember(member, member.guildId)

    @StoreThread
    fun handleGuildMemberRemove(guildId: Long, userId: Long) {
        if (guildMembers[guildId]?.remove(userId) != null) markChanged(GuildsUpdate)
        if (guildMembersComputed[guildId]?.remove(userId) != null) markChanged(ComputedMembersUpdate)
    }

    @StoreThread
    fun handleGuildMembersChunk(chunk: GuildMembersChunk) =
        handleGuildMembersChunk(chunk.guildId, chunk.members.orEmpty())

    @StoreThread
    fun handleGuildMembersChunk(guildId: Long, members: Collection<ApiGuildMember>) =
        members.forEach { handleGuildMember(it, guildId) }

    @StoreThread
    fun handleThreadMembersUpdate(update: ThreadMembersUpdate) =
        handleGuildMembersChunk(update.guildId, update.addedMembers.orEmpty().mapNotNull { it.member })

    @StoreThread
    fun handleThreadMembersUpdate(guildId: Long, members: Collection<ApiGuildMember>) =
        handleGuildMembersChunk(guildId, members)

    @StoreThread
    fun handleThreadMemberListUpdate(update: ThreadMemberListUpdate) =
        handleGuildMembersChunk(update.guildId, update.members.orEmpty().mapNotNull { it.member })

    @StoreThread
    fun handleThreadMemberListUpdate(guildId: Long, members: Collection<ApiGuildMember>) =
        handleGuildMembersChunk(guildId, members)

    @StoreThread
    fun handleGuildScheduledEventUsersFetch(guildId: Long, members: Collection<ApiGuildMember>) =
        handleGuildMembersChunk(guildId, members)

    @StoreThread
    fun handleGuildRoleCreateOrUpdate(guildId: Long, role: GuildRole) {
        putRoles(guildId, listOf(role))
        recomputeMembers(guildId)
    }

    @StoreThread
    fun handleGuildRoleRemove(guildId: Long, roleId: Long) {
        if (guildRoles[guildId]?.remove(roleId) != null) markChanged(GuildsUpdate)
        recomputeMembers(guildId)
    }

    @StoreThread
    fun handleGuildEntityIdsReconciled(
        guildId: Long,
        emojiIds: Set<Long>?,
        stickerIds: Set<Long>?,
    ) {
        val guild = guilds[guildId] ?: return
        val updated = guild.copy(
            emojis = emojiIds?.let { ids -> guild.emojis.filter { it.id in ids } } ?: guild.emojis,
            stickers = stickerIds?.let { ids -> guild.stickers.filter { it.id in ids } } ?: guild.stickers,
        )
        if (updated != guild) {
            guilds[guildId] = updated
            markChanged(GuildsUpdate)
        }
    }

    /** Schedules the timeout clear on the store thread; safe to call from anywhere. */
    fun handleGuildMemberCommunicationEnabled(guildId: Long, userId: Long) = dispatcher.schedule {
        handleGuildMemberCommunicationEnabledInternal(guildId, userId)
    }

    @StoreThread
    internal fun handleGuildMemberCommunicationEnabledInternal(guildId: Long, userId: Long) {
        val member = guildMembers[guildId]?.get(userId) ?: return
        if (member.communicationDisabledUntil == null) return
        val cleared = member.copy(communicationDisabledUntil = null)
        handleGuildMember(cleared, guildId)
        // Timeouts strip permissions, so lifting our own has to feed back into StorePermissions.
        if (userId == meId) onMeCommunicationEnabled(cleared)
    }

    fun getGuild(guildId: Long) = guildsSnapshot[guildId]
    fun getGuilds(): Map<Long, Guild> = guildsSnapshot
    fun getMembers(): Map<Long, Map<Long, GuildMember>> = guildMembersComputedSnapshot
    fun getMember(guildId: Long, userId: Long) = guildMembersComputedSnapshot[guildId]?.get(userId)
    fun getRoles(): Map<Long, Map<Long, GuildRole>> = guildRolesSnapshot
    fun getGuildsJoinedAt(): Map<Long, Long> = guildsJoinedAtSnapshot
    fun getUnavailableGuilds(): Set<Long> = guildsUnavailableSnapshot

    internal fun getGuildsInternal(): Map<Long, Guild> = guilds
    internal fun getGuildMembersInternal(): Map<Long, Map<Long, ApiGuildMember>> = guildMembers
    internal fun getGuildMembersComputedInternal(): Map<Long, Map<Long, GuildMember>> = guildMembersComputed
    internal fun getGuildRolesInternal(): Map<Long, Map<Long, GuildRole>> = guildRoles
    internal fun getGuildsJoinedAtInternal(): Map<Long, Long> = guildsJoinedAt
    internal fun getUnavailableGuildsInternal(): Set<Long> = guildsUnavailable

    fun observeGuilds(): Observable<Map<Long, Guild>> = observe(GuildsUpdate) { guildsSnapshot }
    fun observeGuild(guildId: Long): Observable<Guild?> = observeGuilds().map { it[guildId] }.distinctUntilChanged()
    fun observeGuildIds(): Observable<Set<Long>> = observeGuilds().map { it.keys }.distinctUntilChanged()

    /** The guild a channel belongs to, or `null` for private channels and unknown ids. */
    fun observeFromChannelId(channelId: Long): Observable<Guild?> =
        StoreStream.getChannels().observeChannel(channelId).switchMap { channel ->
            if (channel == null) Observable.just<Guild?>(null) else observeGuild(channel.guildId)
        }

    fun observeComputed(): Observable<Map<Long, Map<Long, GuildMember>>> =
        observe(ComputedMembersUpdate) { guildMembersComputedSnapshot }
    fun observeComputed(guildId: Long): Observable<Map<Long, GuildMember>> =
        observeComputed().map { it[guildId] ?: emptyComputedMap }.distinctUntilChanged()
    fun observeComputed(guildId: Long, userIds: Collection<Long>): Observable<Map<Long, GuildMember>> =
        observeComputed(guildId).map { members -> members.filterKeys(userIds.toSet()::contains) }.distinctUntilChanged()
    fun observeComputedMember(guildId: Long, userId: Long): Observable<GuildMember?> =
        observe(ComputedMembersUpdate) { getMember(guildId, userId) }
    fun observeGuildMember(guildId: Long, userId: Long): Observable<GuildMember?> =
        observeComputed().map { it[guildId]?.get(userId) }.distinctUntilChanged()
    fun observeGuildMembers(guildId: Long): Observable<Map<Long, GuildMember>> =
        observeComputed().map { it[guildId] ?: emptyComputedMap }.distinctUntilChanged()

    fun observeRoles(): Observable<Map<Long, Map<Long, GuildRole>>> = observe(GuildsUpdate) { guildRolesSnapshot }
    fun observeRoles(guildId: Long): Observable<Map<Long, GuildRole>> =
        observeRoles().map { it[guildId] ?: emptyRoles }.distinctUntilChanged()
    fun observeRoles(guildId: Long, roleIds: Collection<Long>): Observable<Map<Long, GuildRole>> =
        observeRoles(guildId).map { roles -> roles.filterKeys(roleIds.toSet()::contains) }.distinctUntilChanged()

    /** Roles highest-first; ties break on the older role, which is what the role list shows. */
    fun observeSortedRoles(guildId: Long): Observable<List<GuildRole>> =
        observeRoles(guildId).map { it.values.sortedWith(RoleUtils.ROLE_COMPARATOR) }.distinctUntilChanged()

    fun observeJoinedAt(): Observable<Map<Long, Long>> =
        observe(GuildsUpdate) { guildsJoinedAtSnapshot }.distinctUntilChanged()

    /** Falls back to "now" for a guild whose member row has not been hydrated yet. */
    fun observeJoinedAt(guildId: Long): Observable<Long> =
        observeJoinedAt().map { getJoinedAtOrNow(it[guildId]) }.distinctUntilChanged()

    fun observeUnavailableGuilds(): Observable<Set<Long>> = observe(GuildsUpdate) { guildsUnavailableSnapshot }

    fun getGeoRestrictedGuilds(): List<GeoRestrictedGuild> = geoRestrictedGuildsSnapshot

    fun observeGeoRestrictedGuilds(): Observable<List<GeoRestrictedGuild>> =
        observe(GuildsUpdate) { geoRestrictedGuildsSnapshot }

    fun observeVerificationLevel(guildId: Long): Observable<GuildVerificationLevel> =
        observeGuild(guildId).map { it?.verificationLevel ?: GuildVerificationLevel.NONE }.distinctUntilChanged()

    /**
     * Every currently timed-out member across all joined guilds.
     *
     * Both the guild set and the member map are real dependencies of the result, so leaving a
     * guild has to re-emit even when no member changed.
     */
    fun observeCommunicationDisabledGuildMembers(): Observable<List<GuildMember>> =
        Observable.combineLatest(observeGuildIds(), observeComputed()) { guildIds, computed ->
            guildIds.flatMap { guildId ->
                computed[guildId]?.values.orEmpty().filter(GuildMember::isCommunicationDisabled)
            }
        }.distinctUntilChanged()

    @StoreThread
    private fun putGuild(guild: ApiGuild) {
        val merged = (guilds[guild.id] ?: Guild()).merge(guild)
        if (merged != guilds[guild.id]) {
            guilds[guild.id] = merged
            markChanged(GuildsUpdate)
        }
    }

    @StoreThread
    private fun ApiGuild.toGeoRestricted() = GeoRestrictedGuild(id, name, icon)

    private fun handleGuildUnavailable(guildId: Long, unavailable: Boolean, remove: Boolean) {
        if (remove) {
            if (unavailable && guildsUnavailable.add(guildId)) markChanged(GuildsUpdate)
        } else if (!unavailable && guildsUnavailable.remove(guildId)) {
            markChanged(GuildsUpdate)
        }
    }

    @StoreThread
    private fun putRoles(guildId: Long, roles: Collection<GuildRole>) {
        val stored = guildRoles.getOrPut(guildId) { HashMap() }
        var changed = false
        roles.forEach { role -> if (stored.put(role.id, role) != role) changed = true }
        if (changed) markChanged(GuildsUpdate)
    }

    @StoreThread
    private fun recomputeMembers(guildId: Long) {
        val members = guildMembers[guildId].orEmpty()
        members.values.toList().forEach { handleGuildMember(it, guildId) }
        handleHasRoleAndJoinedAt(guildId, members)
    }

    /** Records when *we* joined [guildId]; the guild list orders by it. */
    @StoreThread
    private fun handleHasRoleAndJoinedAt(guildId: Long, members: Map<Long, ApiGuildMember>) {
        val me = members[meId] ?: return
        val joinedAt = me.joinedAt?.dateTimeMillis ?: 0L
        if (guildsJoinedAt[guildId] == joinedAt) return
        guildsJoinedAt[guildId] = joinedAt
        markChanged(GuildsUpdate)
    }

    private fun <T> observe(source: ObservationDeck.UpdateSource, generator: () -> T): Observable<T> =
        observationDeck.connectRx(source, generator = generator)

    private fun Guild.merge(api: ApiGuild): Guild {
        // Gson bypasses Kotlin constructors; omitted fields in partial guild payloads are JVM null.
        val incomingName: String? = api.name
        val incomingFeatures: List<GuildFeature>? = api.features
        val incomingNsfwLevel: GuildNsfwLevel? = api.nsfwLevel
        return Guild(
            roles = emptyList(),
            emojis = api.emojis.orEmpty(),
            stickers = api.stickers.orEmpty(),
            name = incomingName?.takeIf(String::isNotEmpty) ?: name,
            description = api.description ?: description,
            defaultMessageNotifications = api.defaultMessageNotifications ?: defaultMessageNotifications,
            id = api.id.takeUnless { it == 0L } ?: id,
            region = api.region ?: region,
            ownerId = api.ownerId.takeUnless { it == 0L } ?: ownerId,
            icon = api.icon ?: icon,
            verificationLevel = api.verificationLevel ?: verificationLevel,
            explicitContentFilter = api.explicitContentFilter ?: explicitContentFilter,
            unavailable = api.unavailable,
            mfaLevel = api.mfaLevel,
            afkTimeout = api.afkTimeout.takeUnless { it == 0 } ?: afkTimeout,
            afkChannelId = api.afkChannelId,
            systemChannelId = api.systemChannelId,
            features = incomingFeatures?.toSet() ?: features,
            memberCount = api.memberCount,
            banner = api.banner,
            splash = api.splash ?: splash,
            premiumTier = api.premiumTier,
            premiumSubscriptionCount = api.premiumSubscriptionCount,
            systemChannelFlags = api.systemChannelFlags,
            joinedAt = api.joinedAt ?: joinedAt,
            rulesChannelId = api.rulesChannelId ?: rulesChannelId,
            publicUpdatesChannelId = api.publicUpdatesChannelId ?: publicUpdatesChannelId,
            preferredLocale = api.preferredLocale ?: preferredLocale,
            welcomeScreen = api.welcomeScreen ?: welcomeScreen,
            profile = api.profile ?: profile,
            maxVideoChannelUsers = api.maxVideoChannelUsers ?: maxVideoChannelUsers,
            vanityUrlCode = api.vanityUrlCode ?: vanityUrlCode,
            approximatePresenceCount = api.approximatePresenceCount.takeUnless { it == 0 } ?: approximatePresenceCount,
            nsfw = api.nsfw,
            hubType = api.hubType,
            safetyAlertsChannelId = api.safetyAlertsChannelId,
            premiumProgressBarEnabled = api.premiumProgressBarEnabled,
            nsfwLevel = incomingNsfwLevel ?: nsfwLevel,
            ownerConfiguredContentLevel = api.ownerConfiguredContentLevel,
            incidentsData = api.incidentsData ?: incidentsData,
            officialMessageColor = api.officialMessageColor ?: officialMessageColor,
        )
    }

    /**
     * Gateway member requests driven directly by a screen rather than by store state.
     *
     * The member list and the mention autocomplete both search members the client has never
     * downloaded, so they ask the gateway for them as the user types.
     */
    object Actions {
        /** Debounce before a partially typed name is sent to the gateway. */
        private const val REQUEST_DEBOUNCE_MS = 1_000L

        /**
         * Requests members of [guildId] matching each emitted search token.
         *
         * In [autocomplete] mode the token is a raw chat-input word, so a leading `@` is stripped
         * and anything else is discarded; otherwise the token is a search box's contents and is
         * used verbatim. Blank tokens are dropped rather than requesting every member.
         */
        @JvmStatic
        fun requestMembers(
            appComponent: AppComponent,
            guildId: Long,
            partialUserNameTokenEmitted: Observable<String?>,
            autocomplete: Boolean,
            requestGuildMembers: (Long, String) -> Unit,
        ) {
            partialUserNameTokenEmitted
                .debounce(REQUEST_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                .map { token ->
                    when {
                        autocomplete -> token?.takeIf { it.isNotEmpty() && it[0] == '@' }?.substring(1)
                        else -> token?.takeIf { it.isNotEmpty() }
                    }
                }
                .filter { it != null }
                .distinctUntilChanged()
                .computationLatest()
                .ui(appComponent)
                .appSubscribe("requestGuildMembers") { token -> requestGuildMembers(guildId, token!!) }
        }

        /**
         * Resolves the member ids holding [roleId] over REST, then asks the gateway for those
         * member objects. The REST call returns ids only, which is why the second hop exists.
         */
        @JvmStatic
        fun requestRoleMembers(
            guildId: Long,
            appComponent: AppComponent,
            roleMemberIds: Observable<List<Long>>,
            storeGatewayConnection: StoreGatewayConnection,
        ) {
            roleMemberIds
                .computationLatest()
                .ui(appComponent)
                .appSubscribe(Actions::class.java) { userIds ->
                    storeGatewayConnection.requestGuildMembers(guildId, userIds = userIds)
                }
        }
    }

    companion object {
        const val GUILDS_CACHE_KEY = "STORE_GUILDS_V34"
        const val GUILD_ROLES_CACHE_KEY = "STORE_GUILD_ROLES_V7"
        const val GUILD_JOINED_AT_CACHE_KEY = "STORE_GUILD_JOINED_AT_V6"

        /** Above this many members a guild's computed map switches to a partitioned copy. */
        internal const val HUGE_GUILD_SIZE = 2000

        private const val CLEAR_COMMUNICATION_DISABLED_INTERVAL_SECONDS = 10L

        /**
         * Shared empty results. Returning the same instance lets `distinctUntilChanged()` drop
         * repeats by identity instead of walking an equal-but-new map on every emission.
         */
        private val emptyComputedMap = emptyMap<Long, GuildMember>()
        private val emptyRoles = emptyMap<Long, GuildRole>()

        val GuildsUpdate = object : ObservationDeck.UpdateSource {}
        val ComputedMembersUpdate = object : ObservationDeck.UpdateSource {}
    }
}
